package com.superprogrammer.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.billing.context.BillingContext;
import com.superprogrammer.billing.service.LlmBillingService;
import com.superprogrammer.billing.service.PointsWalletService;
import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.llm.config.LlmConfig;
import com.superprogrammer.llm.dto.*;
import com.superprogrammer.llm.provider.LlmProviderInterface;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.llm.service.UserLlmProviderService;
import com.superprogrammer.system.service.SystemSettingService;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import com.superprogrammer.billing.service.InflightGateService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmGatewayTest {

    @Mock
    private LlmProviderInterface deepseekProvider;

    @Mock
    private LlmProviderInterface openaiProvider;

    @Mock
    private LlmProviderInterface rerankProvider;

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
    /** 计划5 Step4：组池预检/计费 mock。 */
    @Mock
    private com.superprogrammer.projectgroup.service.ProjectGroupWalletService groupWalletService;
    @Mock
    private InflightGateService inflightGate;
    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private com.superprogrammer.knowledge.trace.RagTraceService ragTraceService;
    @Mock
    private com.superprogrammer.knowledge.trace.RagTraceService.ModelCallScope modelCallScope;

    private LlmGateway gateway;
    private PrometheusMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        when(deepseekProvider.getName()).thenReturn("deepseek");
        lenient().when(deepseekProvider.supports(anyString())).thenReturn(false);
        when(deepseekProvider.supports("deepseek-chat")).thenReturn(true);

        when(openaiProvider.getName()).thenReturn("openai");
        lenient().when(openaiProvider.supports(anyString())).thenReturn(true);

        when(llmConfig.getProviders()).thenReturn(List.of(deepseekProvider, openaiProvider));
        when(rerankProvider.getName()).thenReturn("qwen-rerank-provider");
        when(rerankProvider.supports("configured-rerank-model")).thenReturn(true);
        when(llmConfig.getRerankProviders()).thenReturn(List.of(rerankProvider));
        meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        gateway = new LlmGateway(llmConfig, userLlmProviderService, llmProviderService, objectMapper,
                new com.superprogrammer.llm.config.LlmThinkingProperties(),
                billingService, walletService, groupWalletService, new BizMetrics(meterRegistry), inflightGate, systemSettingService, ragTraceService);
        lenient().when(ragTraceService.beginModelCall(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(modelCallScope);
        lenient().doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(modelCallScope).runWithContext(any(Runnable.class));
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
    void rerank_shouldUseDedicatedRegistryAndRecordUsage() {
        TokenUsage usage = TokenUsage.builder().promptTokens(11).completionTokens(0).totalTokens(11).build();
        RerankResult providerResult = RerankResult.builder()
                .items(List.of(RerankResult.Item.builder().index(1).score(0.9).build()))
                .model("configured-rerank-model").usage(usage).duration(12L).build();
        when(rerankProvider.rerank(any())).thenReturn(providerResult);
        when(rerankProvider.getId()).thenReturn(9L);
        when(rerankProvider.getProviderScope()).thenReturn("GLOBAL");

        RerankResult result = gateway.rerank(RerankRequest.builder()
                .model("configured-rerank-model")
                .query("secret query")
                .documents(List.of("secret doc a", "secret doc b"))
                .build(), 42L);

        assertEquals(1, result.getItems().get(0).getIndex());
        verify(rerankProvider).rerank(any(RerankRequest.class));
        verify(openaiProvider, never()).rerank(any());
        verify(billingService).onSuccess(eq(42L), eq(9L), eq("GLOBAL"),
                eq("configured-rerank-model"), eq("RERANK"), eq(11), eq(0), eq("SUCCESS"), isNull(), isNull());
        verify(ragTraceService).beginModelCall(eq("configured-rerank-model"),
                eq("qwen-rerank-provider"), eq("documents=2,topN=2"), eq("RERANK"));
        verify(modelCallScope).succeed(isNull(), eq(11), eq(0));
    }

    @Test
    void rerank_withoutMatchingProvider_shouldFailClearly() {
        when(llmConfig.getRerankProviders()).thenReturn(List.of());

        RuntimeException error = assertThrows(RuntimeException.class, () -> gateway.rerank(
                RerankRequest.builder().model("missing-rerank-model").query("q")
                        .documents(List.of("a")).build(), 42L));

        assertTrue(error.getMessage().contains("missing-rerank-model"));
        verifyNoInteractions(rerankProvider);
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
        LlmGateway emptyGateway = new LlmGateway(llmConfig, userLlmProviderService, llmProviderService, objectMapper,
                new com.superprogrammer.llm.config.LlmThinkingProperties(),
                billingService, walletService, groupWalletService, new BizMetrics(meterRegistry), inflightGate, systemSettingService, ragTraceService);
        LlmRequest request = LlmRequest.builder().model("unknown").build();
        assertThrows(RuntimeException.class, () -> emptyGateway.chat(request));
    }

    @Test
    void chat_withBlankModel_shouldUseAdminDefault() {
        when(llmConfig.getProviders()).thenReturn(List.of(deepseekProvider));
        when(deepseekProvider.supports("doubao-seed-2.1-code")).thenReturn(true);
        when(systemSettingService.getDefaultChatModel()).thenReturn("doubao-seed-2.1-code");
        when(deepseekProvider.chat(any())).thenReturn(LlmResponse.builder().content("ok").build());

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();

        gateway.chat(request);

        assertEquals("doubao-seed-2.1-code", request.getModel());
        verify(deepseekProvider).chat(request);
    }

    @Test
    void chat_withBlankModelAndNoAdminDefault_shouldFailClearly() {
        when(systemSettingService.getDefaultChatModel()).thenReturn(null);
        LlmRequest request = LlmRequest.builder().model(null).build();

        RuntimeException error = assertThrows(RuntimeException.class, () -> gateway.chat(request));

        assertTrue(error.getMessage().contains("管理员未配置默认对话模型"));
        verifyNoInteractions(deepseekProvider, openaiProvider);
    }

    @Test
    void chat_withExplicitUnavailableModel_shouldNotSilentlyReplaceIt() {
        when(llmConfig.getProviders()).thenReturn(List.of(deepseekProvider));
        when(deepseekProvider.supports("retired-model")).thenReturn(false);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> gateway.chat(LlmRequest.builder().model("retired-model").build()));

        assertTrue(error.getMessage().contains("retired-model"));
        verify(systemSettingService, never()).getDefaultChatModel();
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
                eq("CHAT"), eq(100), eq(50), eq("SUCCESS"), isNull(), isNull(), isNull());
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
                eq("CHAT"), eq(1), eq(0), eq("ESTIMATED"), isNull(), isNull(), isNull());
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
        verify(modelCallScope).fail(contains("boom"));
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
                eq("CHAT"), eq(20), eq(10), eq("SUCCESS"), isNull(), isNull(), isNull());
        verify(modelCallScope).detach();
        verify(modelCallScope, atLeast(2)).runWithContext(any(Runnable.class));
    }

    // ===== 2026-08-17 实测⑤：净化器 carry 尾段流失 =====

    /**
     * mask 开启时 StreamMasker 扣留尾 ≤40 字符；chat 流的 DONE 在网关流之后由服务层追加，
     * 既有「非 CHUNK 事件触发 flush」分支永不执行 → 流终结必须统一补发扣留尾段（否则每条
     * 流式回复末 40 字符静默丢失，max_tokens 截断标记 chunk 也会被整体吞掉）。
     */
    @Test
    void chatStream_sanitizerCarry_flushedAtStreamTerminal() {
        com.superprogrammer.common.security.ai.OutputSanitizer sanitizer =
                new com.superprogrammer.common.security.ai.OutputSanitizer(systemSettingService,
                        mock(com.superprogrammer.common.security.ai.PromptLeakDetector.class));
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "outputSanitizer", sanitizer);
        when(systemSettingService.getAiOutputMaskEnabled()).thenReturn(true);
        when(systemSettingService.getAiPromptLeakEnabled()).thenReturn(false);
        when(deepseekProvider.chatStream(any(), any())).thenAnswer(inv ->
                Flux.just(com.superprogrammer.chat.dto.StreamEvent.chunk("A".repeat(100))));
        when(deepseekProvider.getId()).thenReturn(7L);

        LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();
        List<com.superprogrammer.chat.dto.StreamEvent> events =
                gateway.chatStream(request, 42L).collectList().block();

        assertNotNull(events);
        String all = events.stream()
                .map(e -> e.getContent() == null ? "" : e.getContent())
                .reduce("", String::concat);
        assertEquals(100, all.length(), "流终结须补发扣留尾段（100 字符全量到达，实际=" + all.length() + "）");
    }

    // ===== 归户兜底（层1 咽喉）：忘传 userId → 自动从 BillingContext 归户；无上下文 → 仅采不扣 =====

    @Test
    void chat_noUidButBillingContextSet_autoChargesContextUid() {
        // 新模块免改计费的关键契约：调用方忘传 userId，gateway 自动从 BillingContext 归户照常采+扣。
        BillingContext.set(42L);
        try {
            TokenUsage usage = TokenUsage.builder().promptTokens(8).completionTokens(4).totalTokens(12).build();
            LlmResponse mockResp = LlmResponse.builder()
                    .content("ans").model("deepseek-chat").usage(usage).duration(1L).build();
            when(deepseekProvider.chat(any())).thenReturn(mockResp);
            when(deepseekProvider.getId()).thenReturn(7L);
            when(deepseekProvider.getProviderScope()).thenReturn("GLOBAL");

            LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                    .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                    .build();
            gateway.chat(request);   // 不传 uid（模拟新模块直调 chat(req)）

            verify(walletService).requireAffordable(42L);   // uid 取自 BillingContext
            verify(billingService).onSuccess(eq(42L), eq(7L), eq("GLOBAL"), eq("deepseek-chat"),
                    eq("CHAT"), eq(8), eq(4), eq("SUCCESS"), isNull(), isNull(), isNull());
        } finally {
            BillingContext.clear();
        }
    }

    @Test
    void chat_noUidNoContext_collectsOnlyNullUidNoCharge() {
        assertNull(BillingContext.current(), "无 BillingContext（系统调用场景）");
        TokenUsage usage = TokenUsage.builder().promptTokens(8).completionTokens(4).build();
        LlmResponse mockResp = LlmResponse.builder()
                .content("ans").model("deepseek-chat").usage(usage).duration(1L).build();
        when(deepseekProvider.chat(any())).thenReturn(mockResp);
        when(deepseekProvider.getId()).thenReturn(7L);

        LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();
        gateway.chat(request);   // 不传 uid，无上下文 → uid 解析为 null

        // uid=null → requireAffordable(null) 短路（不扣），onSuccess(null,...) 仅采集不扣费
        verify(walletService).requireAffordable(null);
        verify(billingService).onSuccess(isNull(), eq(7L), any(), eq("deepseek-chat"),
                eq("CHAT"), eq(8), eq(4), eq("SUCCESS"), isNull(), isNull(), isNull());
    }

    // ===== OPS-FR-03 LLM 指标埋点（正好一次，不重不漏）=====

    @Test
    void chat_metrics_successCountsCallTokensLatency() {
        TokenUsage usage = TokenUsage.builder().promptTokens(100).completionTokens(50).totalTokens(150).build();
        LlmResponse mockResp = LlmResponse.builder()
                .content("ans").model("deepseek-chat").usage(usage).duration(1L).build();
        when(deepseekProvider.chat(any())).thenReturn(mockResp);

        LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();
        gateway.chat(request, 42L);

        String out = meterRegistry.scrape();
        assertTrue(out.contains("llm_calls_total{model=\"deepseek-chat\",provider=\"deepseek\",result=\"success\",} 1.0"), out);
        assertTrue(out.contains("llm_tokens_total{direction=\"in\",model=\"deepseek-chat\",provider=\"deepseek\",} 100.0"), out);
        assertTrue(out.contains("llm_tokens_total{direction=\"out\",model=\"deepseek-chat\",provider=\"deepseek\",} 50.0"), out);
        assertTrue(out.contains("llm_latency_seconds_count{model=\"deepseek-chat\",provider=\"deepseek\",} 1.0"), out);
        assertFalse(out.contains("result=\"fail\""), out);
    }

    @Test
    void chat_metrics_failureCountsFailOnce() {
        when(deepseekProvider.chat(any())).thenThrow(new RuntimeException("boom"));

        LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();
        assertThrows(RuntimeException.class, () -> gateway.chat(request, 42L));

        String out = meterRegistry.scrape();
        assertTrue(out.contains("llm_calls_total{model=\"deepseek-chat\",provider=\"deepseek\",result=\"fail\",} 1.0"), out);
        assertFalse(out.contains("result=\"success\""), out);
    }

    @Test
    void chatStream_metrics_completeCountsSuccessAndTokens() {
        when(deepseekProvider.chatStream(any(), any())).thenAnswer(inv -> {
            Consumer<TokenUsage> sink = inv.getArgument(1);
            sink.accept(TokenUsage.builder().promptTokens(20).completionTokens(10).totalTokens(30).build());
            return Flux.<com.superprogrammer.chat.dto.StreamEvent>empty();
        });

        LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();
        gateway.chatStream(request, 42L).collectList().block();

        String out = meterRegistry.scrape();
        assertTrue(out.contains("llm_calls_total{model=\"deepseek-chat\",provider=\"deepseek\",result=\"success\",} 1.0"), out);
        assertTrue(out.contains("llm_tokens_total{direction=\"in\",model=\"deepseek-chat\",provider=\"deepseek\",} 20.0"), out);
        assertFalse(out.contains("result=\"cancel\""), out);
    }

    @Test
    void chatStream_metrics_errorCountsFailOnce() {
        when(deepseekProvider.chatStream(any(), any())).thenReturn(
                Flux.<com.superprogrammer.chat.dto.StreamEvent>error(new RuntimeException("stream boom")));

        LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();
        assertThrows(RuntimeException.class,
                () -> gateway.chatStream(request, 42L).collectList().block());

        String out = meterRegistry.scrape();
        assertTrue(out.contains("llm_calls_total{model=\"deepseek-chat\",provider=\"deepseek\",result=\"fail\",} 1.0"), out);
        assertFalse(out.contains("result=\"success\""), out);
    }

    @Test
    void chatStream_metrics_cancelCountsCancelOnce() {
        // 发一个事件后永不完结；take(1) 拿到首事件即取消上游 → doOnCancel 计 cancel，success/fail 均不动
        com.superprogrammer.chat.dto.StreamEvent ev = com.superprogrammer.chat.dto.StreamEvent.builder()
                .type("delta").build();
        when(deepseekProvider.chatStream(any(), any())).thenReturn(
                Flux.concat(Flux.just(ev), Flux.<com.superprogrammer.chat.dto.StreamEvent>never()));

        LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .build();
        gateway.chatStream(request, 42L).take(1).collectList().block();

        String out = meterRegistry.scrape();
        assertTrue(out.contains("llm_calls_total{model=\"deepseek-chat\",provider=\"deepseek\",result=\"cancel\",} 1.0"), out);
        assertFalse(out.contains("result=\"success\""), out);
        assertFalse(out.contains("result=\"fail\""), out);
    }

    // ===== 计划5 Step4：组池计费透传（chat/embed/rerank）=====

    /** chat 带 gid → 组池预检+组池计费+usage 落 gid；个人 requireAffordable 不走。 */
    @Test
    void chat_withGroup_prepaysAndBillsGroup() {
        TokenUsage usage = TokenUsage.builder().promptTokens(10).completionTokens(5).totalTokens(15).build();
        when(deepseekProvider.chat(any())).thenReturn(LlmResponse.builder()
                .content("ans").model("deepseek-chat").usage(usage).duration(1L).build());
        when(deepseekProvider.getId()).thenReturn(7L);
        when(deepseekProvider.getProviderScope()).thenReturn("GLOBAL");
        when(groupWalletService.requireAffordableGroup(5L, 42L, "CHAT")).thenReturn(java.math.BigDecimal.TEN);

        LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .projectGroupId(5L)
                .build();
        gateway.chat(request, 42L);

        verify(groupWalletService).requireAffordableGroup(5L, 42L, "CHAT");
        verify(walletService, never()).requireAffordable(any());
        verify(billingService).onSuccess(eq(42L), eq(7L), eq("GLOBAL"), eq("deepseek-chat"),
                eq("CHAT"), eq(10), eq(5), eq("SUCCESS"), isNull(), eq(5L), isNull());
    }

    /** 非成员带 gid → 组池预检抛 403 透传前端；provider 不被调用（未调用不记 FAILED）。 */
    @Test
    void chat_nonMemberGroup_throws403WithoutProviderCall() {
        when(groupWalletService.requireAffordableGroup(5L, 42L, "CHAT")).thenThrow(
                new com.superprogrammer.common.exception.BusinessException(
                        com.superprogrammer.common.exception.ErrorCode.FORBIDDEN, "非项目组成员，不可使用组池计费"));

        LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                .projectGroupId(5L)
                .build();
        RuntimeException error = assertThrows(RuntimeException.class, () -> gateway.chat(request, 42L));
        assertTrue(error.getMessage().contains("非项目组成员"));

        verify(deepseekProvider, never()).chat(any());
        verify(billingService, never()).onSuccess(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    /** 显式 gid 空 → 回退 BillingContext 组槽（KB ask 裸线程种入场景），finally 清理防串号。 */
    @Test
    void chat_groupFallbackFromBillingContext() {
        TokenUsage usage = TokenUsage.builder().promptTokens(8).completionTokens(4).totalTokens(12).build();
        when(deepseekProvider.chat(any())).thenReturn(LlmResponse.builder()
                .content("ans").model("deepseek-chat").usage(usage).duration(1L).build());
        when(deepseekProvider.getId()).thenReturn(7L);
        when(groupWalletService.requireAffordableGroup(9L, 42L, "CHAT")).thenReturn(java.math.BigDecimal.TEN);

        com.superprogrammer.billing.context.BillingContext.set(42L, 9L);
        try {
            LlmRequest request = LlmRequest.builder().model("deepseek-chat")
                    .messages(List.of(LlmMessage.builder().role("user").content("hi").build()))
                    .build();
            gateway.chat(request, 42L);

            verify(groupWalletService).requireAffordableGroup(9L, 42L, "CHAT");
            verify(billingService).onSuccess(eq(42L), eq(7L), any(), eq("deepseek-chat"),
                    eq("CHAT"), eq(8), eq(4), eq("SUCCESS"), isNull(), eq(9L), isNull());
        } finally {
            com.superprogrammer.billing.context.BillingContext.clear();
        }
    }

    /** embed 带 gid → 组池预检+usage 落 gid（知识库 ask 链路 query 向量化）。 */
    @Test
    void embed_withGroup_billsGroup() {
        when(llmConfig.getEmbedProviders()).thenReturn(List.of(openaiProvider));
        when(openaiProvider.supports("text-embedding-3")).thenReturn(true);
        when(openaiProvider.embedWithUsage(any(), any())).thenReturn(EmbedResult.builder()
                .embedding(new float[]{0.1f})
                .usage(TokenUsage.builder().promptTokens(8).completionTokens(0).totalTokens(8).build())
                .build());
        when(openaiProvider.getId()).thenReturn(9L);
        when(openaiProvider.getProviderScope()).thenReturn("GLOBAL");

        gateway.embed("hello", "text-embedding-3", 42L, 5L);

        verify(groupWalletService).requireAffordableGroup(5L, 42L, "EMBED");
        verify(billingService).onSuccess(eq(42L), eq(9L), eq("GLOBAL"), eq("text-embedding-3"),
                eq("EMBED"), eq(8), eq(0), eq("SUCCESS"), isNull(), eq(5L));
    }

    /** rerank 带 gid → 组池预检+usage 落 gid（知识库 ask 链路重排）。 */
    @Test
    void rerank_withGroup_billsGroup() {
        TokenUsage usage = TokenUsage.builder().promptTokens(11).completionTokens(0).totalTokens(11).build();
        when(rerankProvider.rerank(any())).thenReturn(RerankResult.builder()
                .items(List.of(RerankResult.Item.builder().index(1).score(0.9).build()))
                .model("configured-rerank-model").usage(usage).duration(12L).build());
        when(rerankProvider.getId()).thenReturn(9L);
        when(rerankProvider.getProviderScope()).thenReturn("GLOBAL");

        gateway.rerank(RerankRequest.builder()
                .model("configured-rerank-model")
                .query("q")
                .documents(List.of("doc a", "doc b"))
                .build(), 42L, 5L);

        verify(groupWalletService).requireAffordableGroup(5L, 42L, "RERANK");
        verify(billingService).onSuccess(eq(42L), eq(9L), eq("GLOBAL"), eq("configured-rerank-model"),
                eq("RERANK"), eq(11), eq(0), eq("SUCCESS"), isNull(), eq(5L));
    }
}
