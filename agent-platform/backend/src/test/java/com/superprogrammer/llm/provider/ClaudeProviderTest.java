package com.superprogrammer.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
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
}
