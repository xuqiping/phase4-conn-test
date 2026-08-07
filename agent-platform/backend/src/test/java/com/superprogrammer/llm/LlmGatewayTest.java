package com.superprogrammer.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.billing.service.LlmBillingService;
import com.superprogrammer.billing.service.PointsWalletService;
import com.superprogrammer.llm.config.LlmConfig;
import com.superprogrammer.llm.dto.*;
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
import java.util.function.Consumer;

import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmGatewayTest {

    @Mock
    private LlmProviderInterface deepseekProvider;

    @Mock
    private LlmProviderInterface openaiProvider;

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
        when(deepseekProvider.getName()).thenReturn("deepseek");
        lenient().when(deepseekProvider.supports(anyString())).thenReturn(false);
        when(deepseekProvider.supports("deepseek-chat")).thenReturn(true);

        when(openaiProvider.getName()).thenReturn("openai");
        lenient().when(openaiProvider.supports(anyString())).thenReturn(true);

        when(llmConfig.getProviders()).thenReturn(List.of(deepseekProvider, openaiProvider));
        gateway = new LlmGateway(llmConfig, userLlmProviderService, llmProviderService, objectMapper, billingService, walletService);
    }

    @Test
    void chat_withDeepSeekModel_shouldUseDeepSeekProvider() {
        LlmResponse mockResp = LlmResponse.builder()
                .content("你好").model("deepseek-chat").duration(100L).build();
        when(deepseekProvider.chat(any())).thenReturn(mockResp);

        LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();
        LlmResponse resp = gateway.chat(request);

        assertEquals("你好", resp.getContent());
        verify(deepseekProvider).chat(any());
        verify(openaiProvider, never()).chat(any());
    }

    @Test
    void chat_withUnknownModel_shouldUseFirstSupportingProvider() {
        LlmResponse mockResp = LlmResponse.builder()
                .content("hi").model("gpt-4").duration(200L).build();
        when(openaiProvider.chat(any())).thenReturn(mockResp);

        LlmRequest request = LlmRequest.builder().model("gpt-4")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();
        LlmResponse resp = gateway.chat(request);

        assertEquals("hi", resp.getContent());
        verify(openaiProvider).chat(any());
    }

    @Test
    void chat_withNoMatchingProvider_shouldThrow() {
        when(llmConfig.getProviders()).thenReturn(List.of());
        LlmGateway emptyGateway = new LlmGateway(llmConfig, userLlmProviderService, llmProviderService, objectMapper, billingService, walletService);
        LlmRequest request = LlmRequest.builder().model("unknown").build();
        assertThrows(RuntimeException.class, () -> emptyGateway.chat(request));
    }

    // ===== Step12 计费出口接线 =====

    @Test
    void chat_withUsage_prechecksAndChargesRealTokens() {
        TokenUsage usage = TokenUsage.builder().promptTokens(100).completionTokens(50).totalTokens(150).build();
        LlmResponse mockResp = LlmResponse.builder()
                .content("ans").model("deepseek-chat").usage(usage).duration(1L).build();
        when(deepseekProvider.chat(any())).thenReturn(mockResp);
        when(deepseekProvider.getId()).thenReturn(7L);
        when(deepseekProvider.getProviderScope()).thenReturn("GLOBAL");

        LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();
        gateway.chat(request, 42L);

        verify(walletService).requireAffordable(42L);
        verify(billingService).onSuccess(eq(42L), eq(7L), eq("GLOBAL"), eq("deepseek-chat"),
                eq("CHAT"), eq(100), eq(50), eq("SUCCESS"));
    }

    @Test
    void chat_noUsage_estimatesAndRecordsEstimated() {
        // usage=null → 估算 input（chars/4），status=ESTIMATED
        LlmResponse mockResp = LlmResponse.builder()
                .content("ans").model("deepseek-chat").duration(1L).build();
        when(deepseekProvider.chat(any())).thenReturn(mockResp);
        when(deepseekProvider.getId()).thenReturn(7L);

        LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("hello").build())) // 5 chars → est 1
                .build();
        gateway.chat(request, 42L);

        verify(billingService).onSuccess(eq(42L), eq(7L), any(), eq("deepseek-chat"),
                eq("CHAT"), eq(1), eq(0), eq("ESTIMATED"));
    }

    @Test
    void chat_failure_recordsFailedAndRethrows() {
        when(deepseekProvider.chat(any())).thenThrow(new RuntimeException("LLM调用失败: boom"));
        when(deepseekProvider.getId()).thenReturn(7L);

        LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();
        assertThrows(RuntimeException.class, () -> gateway.chat(request, 42L));

        verify(billingService).onFailure(eq(42L), eq(7L), any(), eq("deepseek-chat"),
                eq("CHAT"), contains("boom"));
    }

    @Test
    void chatStream_sideSink_chargesOnUsageAndPrechecks() {
        // 捕获 provider 收到的 sink，手动回灌 usage 证明 gateway 接的 sink 会采+扣
        when(deepseekProvider.chatStream(any(), any())).thenAnswer(inv -> {
            Consumer<TokenUsage> sink = inv.getArgument(1);
            sink.accept(TokenUsage.builder().promptTokens(20).completionTokens(10).totalTokens(30).build());
            return Flux.<com.superprogrammer.chat.dto.StreamEvent>empty();
        });
        when(deepseekProvider.getId()).thenReturn(7L);

        LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();
        gateway.chatStream(request, 42L).collectList().block();

        verify(walletService).requireAffordable(42L);
        verify(billingService).onSuccess(eq(42L), eq(7L), any(), eq("deepseek-chat"),
                eq("CHAT"), eq(20), eq(10));
    }
}
