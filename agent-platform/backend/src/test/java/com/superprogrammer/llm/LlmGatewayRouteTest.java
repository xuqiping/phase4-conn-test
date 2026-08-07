package com.superprogrammer.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.billing.service.LlmBillingService;
import com.superprogrammer.billing.service.PointsWalletService;
import com.superprogrammer.llm.config.LlmConfig;
import com.superprogrammer.llm.dto.EmbedResult;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.llm.dto.TokenUsage;
import com.superprogrammer.llm.provider.LlmProviderInterface;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.llm.service.UserLlmProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * FR-003 网关按类型路由：CHAT 行只进 chat 路由，EMBEDDING 行只进 embed 路由，
 * 两条路由互不穿透，报错话术区分「对话/向量 Provider」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmGatewayRouteTest {

    @Mock
    private LlmProviderInterface chatProvider;      // 模拟 CHAT 行

    @Mock
    private LlmProviderInterface embedProvider;     // 模拟 EMBEDDING 行

    @Mock
    private LlmConfig llmConfig;

    @Mock
    private UserLlmProviderService userLlmProviderService;

    @Mock
    private LlmProviderService llmProviderService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private LlmBillingService billingService;

    @Mock
    private PointsWalletService walletService;

    private LlmGateway gateway;

    @BeforeEach
    void setUp() {
        when(chatProvider.getName()).thenReturn("chat-global");
        when(chatProvider.supports("gpt-4o")).thenReturn(true);

        when(embedProvider.getName()).thenReturn("embed-global");
        when(embedProvider.supports("text-embedding-3")).thenReturn(true);

        // 双注册表：chat 表只有 CHAT 行，embed 表只有 EMBEDDING 行
        when(llmConfig.getProviders()).thenReturn(List.of(chatProvider));
        when(llmConfig.getEmbedProviders()).thenReturn(List.of(embedProvider));

        gateway = new LlmGateway(llmConfig, userLlmProviderService, llmProviderService, objectMapper, billingService, walletService);
    }

    @Test
    void chat_withChatRowModel_shouldRouteToChatProvider() {
        LlmResponse mockResp = LlmResponse.builder()
                .content("你好").model("gpt-4o").duration(100L).build();
        when(chatProvider.chat(any())).thenReturn(mockResp);

        LlmRequest request = LlmRequest.builder().model("gpt-4o")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();
        LlmResponse resp = gateway.chat(request);

        assertEquals("你好", resp.getContent());
        verify(chatProvider).chat(any());
        verify(embedProvider, never()).chat(any());
    }

    @Test
    void chat_withEmbeddingRowModel_shouldThrowChatMessage() {
        // EMBEDDING 行的模型不能从 chat 路由触达，话术须含「对话 Provider」
        LlmRequest request = LlmRequest.builder().model("text-embedding-3")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();
        RuntimeException e = assertThrows(RuntimeException.class, () -> gateway.chat(request));
        assertTrue(e.getMessage().contains("对话 Provider"), "话术须区分对话路由: " + e.getMessage());
        assertTrue(e.getMessage().contains("text-embedding-3"));
    }

    @Test
    void embed_withEmbeddingRowModel_shouldRouteToEmbedProvider() {
        when(embedProvider.embedWithUsage(any(), any()))
                .thenReturn(EmbedResult.builder().embedding(new float[]{0.1f, 0.2f}).build());

        float[] vec = gateway.embed("hello", "text-embedding-3");

        assertEquals(2, vec.length);
        verify(embedProvider).embedWithUsage("hello", "text-embedding-3");
        // chat 表不参与 embed 路由
        verify(chatProvider, never()).embed(any(), any());
    }

    @Test
    void embed_withChatOnlyModel_shouldNotFallBackToChatProvider() {
        // 模型只存在于 CHAT 行时，embed 不得回落 chat provider，话术须含「向量 Provider」
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> gateway.embed("hello", "gpt-4o"));
        assertTrue(e.getMessage().contains("向量 Provider"), "话术须区分向量路由: " + e.getMessage());
        assertTrue(e.getMessage().contains("gpt-4o"));
        verify(chatProvider, never()).embed(any(), any());
    }

    @Test
    void embed_withUserId_shouldStillUseGlobalEmbedOnly() {
        // 用户级 provider 是 CHAT-only 覆盖：embed 带 userId 也只走全局 EMBEDDING 行
        when(embedProvider.embedWithUsage(any(), any()))
                .thenReturn(EmbedResult.builder().embedding(new float[]{0.5f}).build());

        float[] vec = gateway.embed("hello", "text-embedding-3", 42L);

        assertEquals(1, vec.length);
        verify(embedProvider).embedWithUsage("hello", "text-embedding-3");
        // 不查用户级 provider 列表
        verify(userLlmProviderService, never()).listByUser(any());
    }

    // ===== Step12 embed 计费出口接线 =====

    @Test
    void embed_withRealUsage_chargesEmbedSuccess() {
        TokenUsage usage = TokenUsage.builder().promptTokens(8).completionTokens(0).totalTokens(8).build();
        when(embedProvider.embedWithUsage(any(), any()))
                .thenReturn(EmbedResult.builder().embedding(new float[]{0.1f}).usage(usage).build());
        when(embedProvider.getId()).thenReturn(9L);
        when(embedProvider.getProviderScope()).thenReturn("GLOBAL");

        gateway.embed("hello", "text-embedding-3", 42L);

        verify(walletService).requireAffordable(42L);
        verify(billingService).onSuccess(eq(42L), eq(9L), eq("GLOBAL"), eq("text-embedding-3"),
                eq("EMBED"), eq(8), eq(0), eq("SUCCESS"));
    }

    @Test
    void embed_withoutUsage_estimatesAndRecordsEstimated() {
        // usage=null → 估算 input（"hello" 5 chars → est 1），status=ESTIMATED
        when(embedProvider.embedWithUsage(any(), any()))
                .thenReturn(EmbedResult.builder().embedding(new float[]{0.1f}).build());
        when(embedProvider.getId()).thenReturn(9L);

        gateway.embed("hello", "text-embedding-3", 42L);

        verify(billingService).onSuccess(eq(42L), eq(9L), any(), eq("text-embedding-3"),
                eq("EMBED"), eq(1), eq(0), eq("ESTIMATED"));
    }
}
