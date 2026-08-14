package com.superprogrammer.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.superprogrammer.llm.dto.*;
import com.superprogrammer.chat.dto.StreamEvent;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAICompatibleProviderTest {

    private MockWebServer server;
    private OpenAICompatibleProvider provider;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // FR-001：endpoint 即完整请求 URL，provider 零拼接直发
        String endpoint = server.url("/v1/chat/completions").toString();
        provider = new OpenAICompatibleProvider("test", endpoint, "test-key", List.of("deepseek-chat"), mapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void chat_shouldReturnResponse() throws Exception {
        String mockResponse = """
        {
            "id": "chatcmpl-123",
            "model": "deepseek-chat",
            "choices": [{"message": {"role": "assistant", "content": "Hello!"}}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
        }
        """;
        server.enqueue(new MockResponse()
                .setBody(mockResponse)
                .setHeader("Content-Type", "application/json"));

        LlmRequest request = LlmRequest.builder()
                .model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .build();

        LlmResponse response = provider.chat(request);

        assertEquals("Hello!", response.getContent());
        assertEquals(10, response.getUsage().getPromptTokens());
        assertEquals(15, response.getUsage().getTotalTokens());
        assertEquals("deepseek-chat", response.getModel());
        assertNotNull(response.getDuration());
        // FR-001：发出的请求路径 == 配置的 endpoint 原样（零拼接）
        assertEquals("/v1/chat/completions", server.takeRequest().getPath());
    }

    @Test
    void chat_shouldHonorPerRequestTimeout() {
        server.enqueue(new MockResponse()
                .setBody("{\"choices\":[{\"message\":{\"content\":\"late\"}}]}")
                .setBodyDelay(500, TimeUnit.MILLISECONDS)
                .setHeader("Content-Type", "application/json"));

        LlmRequest request = LlmRequest.builder()
                .model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .timeoutMs(50)
                .build();

        RuntimeException error = assertThrows(RuntimeException.class, () -> provider.chat(request));
        assertTrue(error.getMessage().contains("LLM"));
    }

    @Test
    void embed_shouldPostToExactEndpoint() throws Exception {
        // EMBEDDING 行的 endpoint 即完整 embed URL（V60 补全 /embeddings）
        OpenAICompatibleProvider embedProvider = new OpenAICompatibleProvider(
                "emb", server.url("/v1/embeddings").toString(), "k", List.of("emb-1"), mapper);
        server.enqueue(new MockResponse()
                .setBody("{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}")
                .setHeader("Content-Type", "application/json"));

        float[] vec = embedProvider.embed("hello", "emb-1");

        assertEquals(3, vec.length);
        assertEquals(0.1f, vec[0], 1e-6);
        // FR-001：embed 也直发 endpoint 原样，不再 baseUrl+"/embeddings"
        assertEquals("/v1/embeddings", server.takeRequest().getPath());
    }

    @Test
    void embedWithUsage_shouldParseUsage() throws Exception {
        // Step11：embed 响应带 /usage → embedWithUsage 返向量+usage
        OpenAICompatibleProvider embedProvider = new OpenAICompatibleProvider(
                "emb", server.url("/v1/embeddings").toString(), "k", List.of("emb-1"), mapper);
        server.enqueue(new MockResponse()
                .setBody("{\"data\":[{\"embedding\":[0.1,0.2,0.3]}],\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}")
                .setHeader("Content-Type", "application/json"));

        com.superprogrammer.llm.dto.EmbedResult res = embedProvider.embedWithUsage("hello", "emb-1");

        assertEquals(3, res.getEmbedding().length);
        assertEquals(0.1f, res.getEmbedding()[0], 1e-6);
        assertNotNull(res.getUsage());
        assertEquals(7, res.getUsage().getPromptTokens());
        assertEquals(0, res.getUsage().getCompletionTokens()); // embed 无 completion
        assertEquals(7, res.getUsage().getTotalTokens());
    }

    @Test
    void embedWithUsage_shouldReturnNullUsageWhenAbsent() throws Exception {
        // Step11：embed 响应无 usage → usage=null（gateway 估算兜底）
        OpenAICompatibleProvider embedProvider = new OpenAICompatibleProvider(
                "emb", server.url("/v1/embeddings").toString(), "k", List.of("emb-1"), mapper);
        server.enqueue(new MockResponse()
                .setBody("{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}")
                .setHeader("Content-Type", "application/json"));

        com.superprogrammer.llm.dto.EmbedResult res = embedProvider.embedWithUsage("hello", "emb-1");

        assertEquals(3, res.getEmbedding().length);
        assertNull(res.getUsage());
    }

    @Test
    void qwenMultimodalEmbed_shouldUseContentsParametersAndParseOutput() throws Exception {
        OpenAICompatibleProvider embedProvider = new OpenAICompatibleProvider(
                "qwen-emb",
                server.url("/v1/services/embeddings/multimodal-embedding/multimodal-embedding").toString(),
                "k", List.of("configured-embedding-model"), mapper);
        StringBuilder vector = new StringBuilder();
        for (int i = 0; i < 2048; i++) {
            if (i > 0) vector.append(',');
            vector.append(i == 0 ? "0.25" : "0.0");
        }
        server.enqueue(new MockResponse()
                .setBody("{\"output\":{\"embeddings\":[{\"embedding\":[" + vector
                        + "]}]},\"usage\":{\"input_tokens\":9}}")
                .setHeader("Content-Type", "application/json"));

        EmbedResult result = embedProvider.embedWithUsage("商品描述文本", "configured-embedding-model");

        RecordedRequest request = server.takeRequest();
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertEquals("configured-embedding-model", body.path("model").asText());
        assertEquals("商品描述文本", body.at("/input/contents/0/text").asText());
        assertTrue(body.at("/parameters/enable_fusion").asBoolean());
        assertEquals(2048, body.at("/parameters/dimension").asInt());
        assertEquals(2048, result.getEmbedding().length);
        assertEquals(0.25f, result.getEmbedding()[0], 1e-6);
        assertNotNull(result.getUsage());
        assertEquals(9, result.getUsage().getPromptTokens());
        assertEquals(9, result.getUsage().getTotalTokens());
    }

    @Test
    void qwenMultimodalEmbed_shouldFailWhenOutputEmbeddingMissing() {
        OpenAICompatibleProvider embedProvider = new OpenAICompatibleProvider(
                "qwen-emb",
                server.url("/v1/services/embeddings/multimodal-embedding/multimodal-embedding").toString(),
                "k", List.of("configured-embedding-model"), mapper);
        server.enqueue(new MockResponse()
                .setBody("{\"output\":{\"embeddings\":[]}}")
                .setHeader("Content-Type", "application/json"));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> embedProvider.embedWithUsage("hello", "configured-embedding-model"));

        assertTrue(error.getMessage().contains("embedding"));
    }

    @Test
    void qwenMultimodalEmbed_shouldFailWhenDimensionIsNot2048() {
        OpenAICompatibleProvider embedProvider = new OpenAICompatibleProvider(
                "qwen-emb",
                server.url("/v1/services/embeddings/multimodal-embedding/multimodal-embedding").toString(),
                "k", List.of("configured-embedding-model"), mapper);
        server.enqueue(new MockResponse()
                .setBody("{\"output\":{\"embeddings\":[{\"embedding\":[0.1,0.2]}]}}")
                .setHeader("Content-Type", "application/json"));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> embedProvider.embedWithUsage("hello", "configured-embedding-model"));

        assertTrue(error.getMessage().contains("embedding"));
    }

    @Test
    void supports_shouldMatchModelName() {
        assertTrue(provider.supports("deepseek-chat"));
        assertFalse(provider.supports("gpt-4"));
        assertFalse(provider.supports("any-model"));
    }

    @Test
    void supports_emptyModelList_shouldNotHijackExplicitModel() {
        OpenAICompatibleProvider unconfiguredProvider = new OpenAICompatibleProvider(
                "unconfigured", server.url("/v1/chat/completions").toString(), "k", List.of(), mapper);

        assertFalse(unconfiguredProvider.supports("doubao-seed-2.1-code"),
                "未配置模型列表的 Provider 不得宣称支持所有模型并抢占显式选择");
    }

    @Test
    void getName_shouldReturnName() {
        assertEquals("test", provider.getName());
    }

    @Test
    void chatStream_shouldParseSseChunks() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"content":"Hello"}}]}

                        data: {"choices":[{"delta":{"content":"!"}}]}

                        data: [DONE]

                        """));

        LlmRequest request = LlmRequest.builder()
                .model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .stream(true)
                .build();

        List<String> chunks = provider.chatStream(request)
                .map(com.superprogrammer.chat.dto.StreamEvent::getContent)
                .collectList()
                .block();

        assertEquals(List.of("Hello", "!"), chunks);
    }

    @Test
    void chatStream_shouldCaptureUsageViaSideChannel() throws Exception {
        // Step9：流末 chunk（choices 空）带 usage，content 为空会被过滤，但 usage 须经 side-channel 采到
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"content":"Hi"}}]}

                        data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}

                        data: [DONE]

                        """));

        LlmRequest request = LlmRequest.builder()
                .model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .stream(true)
                .build();

        AtomicReference<TokenUsage> captured = new AtomicReference<>();
        List<String> chunks = provider.chatStream(request, captured::set)
                .map(StreamEvent::getContent)
                .collectList()
                .block();

        // usage chunk content 空 → 不进发出流（SSE 序列不变）
        assertEquals(List.of("Hi"), chunks);
        // side-channel 采到 usage
        assertNotNull(captured.get());
        assertEquals(12, captured.get().getPromptTokens());
        assertEquals(8, captured.get().getCompletionTokens());
        assertEquals(20, captured.get().getTotalTokens());
    }

    @Test
    void chatStream_shouldRequestIncludeUsage() throws Exception {
        // Step9：请求体须带 stream_options.include_usage=true
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"));

        LlmRequest request = LlmRequest.builder()
                .model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .stream(true)
                .build();

        provider.chatStream(request, usage -> {}).collectList().block();

        String sentBody = server.takeRequest().getBody().readUtf8();
        assertTrue(sentBody.contains("\"stream_options\""), "请求体须含 stream_options");
        assertTrue(sentBody.contains("\"include_usage\":true"), "请求体须含 include_usage=true");
    }
}
