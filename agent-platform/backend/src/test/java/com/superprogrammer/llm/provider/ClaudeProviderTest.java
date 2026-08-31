package com.superprogrammer.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.llm.dto.ThinkingLevel;
import com.superprogrammer.llm.dto.TokenUsage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeProviderTest {

    private MockWebServer server;
    private ClaudeProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // FR-001：endpoint 即完整请求 URL（…/v1/messages），provider 零拼接直发
        provider = new ClaudeProvider(
                server.url("/v1/messages").toString(),
                "test-key",
                List.of("k2.6"),
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void supports_emptyModelList_shouldNotInferModelsFromNamePrefix() {
        ClaudeProvider unconfiguredProvider = new ClaudeProvider(
                server.url("/v1/messages").toString(), "test-key", List.of(), new ObjectMapper());

        assertFalse(unconfiguredProvider.supports("claude-3-5-sonnet"),
                "未配置模型列表时不得通过名称前缀隐式认领模型");
    }

    @Test
    void chatStream_shouldParseSseLinesInsideChunk() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        event: message_start
                        data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"k2.6"}}

                        event: content_block_start
                        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"!"}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """));

        LlmRequest request = LlmRequest.builder()
                .model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .stream(true)
                .build();

        List<String> chunks = provider.chatStream(request)
                .map(StreamEvent::getContent)
                .collectList()
                .block();

        assertEquals(List.of("hello", "!"), chunks);
        // FR-001：发出的请求路径 == 配置的 endpoint 原样（零拼接）
        assertEquals("/v1/messages", server.takeRequest().getPath());
    }

    @Test
    void chatStream_shouldCaptureUsageViaSideChannel() throws Exception {
        // Step10：Claude usage 拆在 message_start(input_tokens)+message_delta(output_tokens)，经 side-channel 合并采到
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        event: message_start
                        data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":25,"output_tokens":1}}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}

                        event: message_delta
                        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":18}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """));

        LlmRequest request = LlmRequest.builder()
                .model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .stream(true)
                .build();

        AtomicReference<TokenUsage> captured = new AtomicReference<>();
        List<String> chunks = provider.chatStream(request, captured::set)
                .map(StreamEvent::getContent)
                .collectList()
                .block();

        // usage 事件无 content → 不进发出流
        assertEquals(List.of("hi"), chunks);
        // 合并 input(25) + output(18) = 43
        assertNotNull(captured.get());
        assertEquals(25, captured.get().getPromptTokens());
        assertEquals(18, captured.get().getCompletionTokens());
        assertEquals(43, captured.get().getTotalTokens());
    }

    @Test
    void chatStream_maxTokensStop_shouldAppendVisibleTruncationMarker() throws Exception {
        // 2026-08-16 用户实测③：上游 max_tokens 截断此前静默成正常完成——现须追加可见标记 chunk
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        event: message_start
                        data: {"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":1}}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"半句"}}

                        event: message_delta
                        data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":4096}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """));

        LlmRequest request = LlmRequest.builder()
                .model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .stream(true)
                .build();

        List<String> chunks = provider.chatStream(request)
                .map(StreamEvent::getContent)
                .collectList()
                .block();

        assertEquals(2, chunks.size());
        assertEquals("半句", chunks.get(0));
        assertTrue(chunks.get(1).contains("截断"), "max_tokens 截断须追加可见标记，实际=" + chunks.get(1));
    }

    @Test
    void chatStream_normalEnd_shouldNotAppendMarker() throws Exception {
        // end_turn 正常结束不得追加标记（防噪音污染正文）
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        event: message_start
                        data: {"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":1}}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"完整回答"}}

                        event: message_delta
                        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """));

        LlmRequest request = LlmRequest.builder()
                .model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .stream(true)
                .build();

        List<String> chunks = provider.chatStream(request)
                .map(StreamEvent::getContent)
                .collectList()
                .block();

        assertEquals(List.of("完整回答"), chunks);
    }

    @Test
    void chat_disableThinking_shouldSendThinkingDisabledParam() throws Exception {
        // 内部 JSON 蒸馏调用关思考：思考与正文共享 max_tokens 预算，不关会被思考吃满致 JSON 截断
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}],\"model\":\"k2.6\",\"usage\":{}}"));

        LlmRequest request = LlmRequest.builder()
                .model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("总结").build()))
                .disableThinking(true)
                .build();

        provider.chat(request);

        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"),
                "disableThinking=true 须发 thinking.type=disabled，实际=" + body);
    }

    @Test
    void chat_default_shouldNotSendThinkingParam() throws Exception {
        // 默认对话流不受影响：不发 thinking 参数
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"model\":\"k2.6\",\"usage\":{}}"));

        LlmRequest request = LlmRequest.builder()
                .model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .build();

        provider.chat(request);

        String body = server.takeRequest().getBody().readUtf8();
        assertFalse(body.contains("\"thinking\""), "默认不得带 thinking 参数，实际=" + body);
    }

    // ==================== 9x-1（V160 D3）：缓存命中口径归一 ====================

    @Test
    void chat_nonStream_cacheFields_mergedInputAndCachedLeg() throws Exception {
        // input=100 + cache_creation=10 → 计费输入基数 110；cache_read=50 → cachedTokens 50
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"content":[{"type":"text","text":"hi"}],"model":"k2.6","stop_reason":"end_turn",
                         "usage":{"input_tokens":100,"cache_creation_input_tokens":10,
                                  "cache_read_input_tokens":50,"output_tokens":20}}
                        """));

        LlmRequest request = LlmRequest.builder()
                .model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .build();

        LlmResponse response = provider.chat(request);
        assertEquals(110, response.getUsage().getPromptTokens());
        assertEquals(50L, response.getUsage().getCachedTokens());
        assertEquals(20, response.getUsage().getCompletionTokens());
        // totalTokens 维持 input+output 信息口径（120）
        assertEquals(120, response.getUsage().getTotalTokens());
    }

    @Test
    void chat_nonStream_cacheAbsent_keepsLegacySemantics() throws Exception {
        // 老响应无 cache 字段 → cachedTokens=null、promptTokens=input（老口径逐分一致）
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"model\":\"k2.6\","
                        + "\"usage\":{\"input_tokens\":100,\"output_tokens\":20}}"));

        LlmRequest request = LlmRequest.builder()
                .model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .build();

        LlmResponse response = provider.chat(request);
        assertEquals(100, response.getUsage().getPromptTokens());
        assertNull(response.getUsage().getCachedTokens());
    }

    @Test
    void chatStream_cacheFields_mergedAcrossStartAndDelta() throws Exception {
        // message_start 带 cache_creation/cache_read，message_delta 只带 output → 合并后口径完整
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        event: message_start
                        data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":100,"cache_creation_input_tokens":10,"cache_read_input_tokens":50,"output_tokens":1}}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}

                        event: message_delta
                        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":20}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """));

        LlmRequest request = LlmRequest.builder()
                .model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .stream(true)
                .build();

        AtomicReference<TokenUsage> captured = new AtomicReference<>();
        List<String> chunks = provider.chatStream(request, captured::set)
                .map(StreamEvent::getContent)
                .collectList()
                .block();

        assertEquals(List.of("hi"), chunks);
        assertNotNull(captured.get());
        assertEquals(110, captured.get().getPromptTokens());
        assertEquals(50L, captured.get().getCachedTokens());
        assertEquals(20, captured.get().getCompletionTokens());
    }

    // ==================== 修复IX-1 A2：思考强度三档（Anthropic 协议） ====================

    /** 档位响应体统一构造（非流式，返回原始请求 body）。 */
    private String chatAndCaptureBody(LlmRequest request) throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"model\":\"k2.6\",\"usage\":{}}"));
        provider.chat(request);
        return server.takeRequest().getBody().readUtf8();
    }

    @Test
    void chat_thinkingStandard_shouldSendEnabledWithBudget() throws Exception {
        String body = chatAndCaptureBody(LlmRequest.builder()
                .model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .thinkingLevel(ThinkingLevel.STANDARD)
                .build());
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":2048}"),
                "STANDARD 档须发 enabled+2048，实际=" + body);
        // max_tokens 默认 8192 > 2048 预算 → 不抬不降
        assertTrue(body.contains("\"max_tokens\":8192"), "max_tokens 大于预算时不动，实际=" + body);
    }

    @Test
    void chat_thinkingDeep_budgetBeyondMaxTokens_shouldClampUp() throws Exception {
        String body = chatAndCaptureBody(LlmRequest.builder()
                .model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .thinkingLevel(ThinkingLevel.DEEP)
                .build());
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":8192}"),
                "DEEP 档须发 enabled+8192，实际=" + body);
        // Anthropic 硬约束 max_tokens > budget：默认 8192 ≤ 8192 → 抬到 9216
        assertTrue(body.contains("\"max_tokens\":9216"), "max_tokens 须 clamp 到 budget+1024，实际=" + body);
    }

    @Test
    void chat_thinkingDeep_customBudgets_andClampToAnthropicFloor() throws Exception {
        // 自定义预算 4096/16384：max_tokens 8192 < 16384 → 抬到 17408；低配 512 → clamp 1024
        ClaudeProvider custom = new ClaudeProvider("claude", server.url("/v1/messages").toString(), "key",
                List.of("k2.6"), new ObjectMapper(), null, "GLOBAL", 4096, 16384);
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"model\":\"k2.6\",\"usage\":{}}"));
        custom.chat(LlmRequest.builder().model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .thinkingLevel(ThinkingLevel.DEEP).build());
        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("\"budget_tokens\":16384"), "自定义深度预算须生效，实际=" + body);
        assertTrue(body.contains("\"max_tokens\":17408"), "max_tokens 须随预算抬到 16384+1024，实际=" + body);

        ClaudeProvider low = new ClaudeProvider("claude", server.url("/v1/messages").toString(), "key",
                List.of("k2.6"), new ObjectMapper(), null, "GLOBAL", 512, 600);
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"model\":\"k2.6\",\"usage\":{}}"));
        low.chat(LlmRequest.builder().model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .thinkingLevel(ThinkingLevel.STANDARD).build());
        String lowBody = server.takeRequest().getBody().readUtf8();
        assertTrue(lowBody.contains("\"budget_tokens\":1024"),
                "配置低于 Anthropic 下限时须 clamp 到 1024，实际=" + lowBody);
    }

    @Test
    void chat_thinkingLevelOff_shouldBeatDisableThinkingPriorityAndSendDisabled() throws Exception {
        // 优先级锁：thinkingLevel 显式 OFF 与老 disableThinking=true 同发 disabled；显式 STANDARD 压过 disableThinking
        String body = chatAndCaptureBody(LlmRequest.builder()
                .model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .thinkingLevel(ThinkingLevel.OFF)
                .build());
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"), "OFF 档须发 disabled，实际=" + body);

        String body2 = chatAndCaptureBody(LlmRequest.builder()
                .model("k2.6")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .disableThinking(true)
                .thinkingLevel(ThinkingLevel.STANDARD)
                .build());
        assertTrue(body2.contains("\"thinking\":{\"type\":\"enabled\""),
                "thinkingLevel 优先级须高于 disableThinking，实际=" + body2);
    }
}
