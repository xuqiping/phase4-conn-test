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

    // ---- C5 多模态 embed（WP5 Step1）：contents 拼装 / 协议拒绝 / 熔断 ----

    @Test
    void multimodalEmbed_imageAndTextParts_useContentsArray() throws Exception {
        // 多模态协议端点：text+image 两段 → contents 数组按序透传；
        // Phase4 实测修复（Bug #6）：image 段须带 data URI 前缀（裸 Base64 网关 400）
        OpenAICompatibleProvider embedProvider = new OpenAICompatibleProvider(
                "qwen-mm-a",
                server.url("/v1/services/embeddings/multimodal-embedding/multimodal-embedding").toString(),
                "k", List.of("mm-1"), mapper);
        StringBuilder vector = new StringBuilder();
        for (int i = 0; i < 2048; i++) {
            if (i > 0) vector.append(',');
            vector.append(i == 0 ? "0.5" : "0.0");
        }
        server.enqueue(new MockResponse()
                .setBody("{\"output\":{\"embeddings\":[{\"embedding\":[" + vector
                        + "]}]},\"usage\":{\"input_tokens\":13}}")
                .setHeader("Content-Type", "application/json"));

        EmbedResult result = embedProvider.embedWithUsage(
                List.of(com.superprogrammer.llm.dto.EmbedContentPart.ofText("产品截图"),
                        com.superprogrammer.llm.dto.EmbedContentPart.ofImage("aGVsbG8=")),
                "mm-1");

        RecordedRequest request = server.takeRequest();
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertEquals("产品截图", body.at("/input/contents/0/text").asText());
        assertEquals("data:image/png;base64,aGVsbG8=", body.at("/input/contents/1/image").asText());
        assertTrue(body.at("/parameters/enable_fusion").asBoolean());
        assertEquals(2048, result.getEmbedding().length);
        assertEquals(0.5f, result.getEmbedding()[0], 1e-6);
        assertEquals(13, result.getUsage().getPromptTokens());
    }

    @Test
    void toImageDataUri_magicPrefixAndPassthrough() {
        // Phase4 实测修复（Bug #6）：Base64 头魔数嗅探 → data URI；已是 data: 原样
        assertEquals("data:image/png;base64,iVBORw0KGgoAAA",
                OpenAICompatibleProvider.toImageDataUri("iVBORw0KGgoAAA"));
        assertEquals("data:image/jpeg;base64,/9j/4AAQ",
                OpenAICompatibleProvider.toImageDataUri("/9j/4AAQ"));
        assertEquals("data:image/gif;base64,R0lGODlh",
                OpenAICompatibleProvider.toImageDataUri("R0lGODlh"));
        assertEquals("data:image/webp;base64,UklGRpoA",
                OpenAICompatibleProvider.toImageDataUri("UklGRpoA"));
        // 嗅不出（如短串/异形编码）默认 png；已是 data URI 不重复包
        assertEquals("data:image/png;base64,aGVsbG8=",
                OpenAICompatibleProvider.toImageDataUri("aGVsbG8="));
        assertEquals("data:image/png;base64,iVBORw0KGgo",
                OpenAICompatibleProvider.toImageDataUri("data:image/png;base64,iVBORw0KGgo"));
    }

    @Test
    void multimodalEmbed_plainEndpointRejectsImagePartWithoutRequest() {
        // 普通 /embeddings 端点 + 图片段 → 立即拒绝且不发 HTTP（协议不同形，不盲试）
        OpenAICompatibleProvider embedProvider = new OpenAICompatibleProvider(
                "plain-emb", server.url("/v1/embeddings").toString(), "k", List.of("emb-1"), mapper);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> embedProvider.embedWithUsage(
                        List.of(com.superprogrammer.llm.dto.EmbedContentPart.ofImage("aGVsbG8=")), "emb-1"));

        assertTrue(error.getMessage().contains("不支持图片输入"));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void multimodalEmbed_failureOpensBreaker_fastFailWithoutRetry() throws Exception {
        // 失败一次 → 熔断 10min：紧随的第二次调用 fast-fail，不发第二笔 HTTP
        OpenAICompatibleProvider embedProvider = new OpenAICompatibleProvider(
                "qwen-mm-b",
                server.url("/v1/services/embeddings/multimodal-embedding/multimodal-embedding").toString(),
                "k", List.of("mm-2"), mapper);
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));

        assertThrows(RuntimeException.class, () -> embedProvider.embedWithUsage(
                List.of(com.superprogrammer.llm.dto.EmbedContentPart.ofImage("aGVsbG8=")), "mm-2"));
        RuntimeException second = assertThrows(RuntimeException.class, () -> embedProvider.embedWithUsage(
                List.of(com.superprogrammer.llm.dto.EmbedContentPart.ofImage("aGVsbG8=")), "mm-2"));

        assertTrue(second.getMessage().contains("熔断"));
        assertEquals(1, server.getRequestCount());   // 只打了第一笔，第二笔被熔断拦截
    }

    @Test
    void rerank_shouldSendQwenRequestAndPreserveProviderOrder() throws Exception {
        OpenAICompatibleProvider rerankProvider = new OpenAICompatibleProvider(
                "qwen-rerank", server.url("/v1/reranks").toString(), "k",
                List.of("configured-rerank-model"), mapper);
        server.enqueue(new MockResponse()
                .setBody("{\"results\":[{\"index\":2,\"relevance_score\":0.91},"
                        + "{\"index\":0,\"relevance_score\":0.72}],"
                        + "\"usage\":{\"input_tokens\":11,\"total_tokens\":11}}")
                .setHeader("Content-Type", "application/json"));

        RerankResult result = rerankProvider.rerank(RerankRequest.builder()
                .model("configured-rerank-model")
                .query("什么是文本排序模型")
                .documents(List.of("文本排序模型", "量子计算", "预训练模型改进排序"))
                .build());

        JsonNode body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("configured-rerank-model", body.path("model").asText());
        assertEquals("什么是文本排序模型", body.path("query").asText());
        assertEquals(3, body.path("documents").size());
        assertEquals(3, body.path("top_n").asInt());
        assertFalse(body.path("instruct").asText().isBlank());
        assertEquals(List.of(2, 0), result.getItems().stream().map(RerankResult.Item::getIndex).toList());
        assertEquals(0.91, result.getItems().get(0).getScore(), 1e-6);
        assertEquals(11, result.getUsage().getPromptTokens());
    }

    @Test
    void rerank_shouldRejectDuplicateOrOutOfRangeIndexes() {
        OpenAICompatibleProvider rerankProvider = new OpenAICompatibleProvider(
                "qwen-rerank", server.url("/v1/reranks").toString(), "k",
                List.of("configured-rerank-model"), mapper);
        server.enqueue(new MockResponse()
                .setBody("{\"results\":[{\"index\":0,\"relevance_score\":0.9},"
                        + "{\"index\":0,\"relevance_score\":0.8}]}")
                .setHeader("Content-Type", "application/json"));

        assertThrows(RuntimeException.class, () -> rerankProvider.rerank(RerankRequest.builder()
                .model("configured-rerank-model").query("q").documents(List.of("a", "b")).build()));

        server.enqueue(new MockResponse()
                .setBody("{\"results\":[{\"index\":2,\"relevance_score\":0.9}]}")
                .setHeader("Content-Type", "application/json"));
        assertThrows(RuntimeException.class, () -> rerankProvider.rerank(RerankRequest.builder()
                .model("configured-rerank-model").query("q").documents(List.of("a", "b")).build()));
    }

    @Test
    void rerank_shouldRejectEmptyResults() {
        OpenAICompatibleProvider rerankProvider = new OpenAICompatibleProvider(
                "qwen-rerank", server.url("/v1/reranks").toString(), "k",
                List.of("configured-rerank-model"), mapper);
        server.enqueue(new MockResponse().setBody("{\"results\":[]}")
                .setHeader("Content-Type", "application/json"));

        assertThrows(RuntimeException.class, () -> rerankProvider.rerank(RerankRequest.builder()
                .model("configured-rerank-model").query("q").documents(List.of("a")).build()));
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

    // ==================== 9x-1（V160 D3）：缓存命中口径归一 ====================

    @Test
    void chat_nonStream_cachedDetails_netPromptAndCachedLeg() throws Exception {
        // prompt=100 含命中 40 → 计费输入基数 60 + cachedTokens 40；total 取协议原值 130
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"choices":[{"message":{"content":"hi"}}],"model":"deepseek-chat",
                         "usage":{"prompt_tokens":100,"completion_tokens":7,"total_tokens":130,
                                  "prompt_tokens_details":{"cached_tokens":40}}}
                        """));

        LlmRequest request = LlmRequest.builder()
                .model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .build();

        LlmResponse response = provider.chat(request);
        assertEquals(60, response.getUsage().getPromptTokens());
        assertEquals(40L, response.getUsage().getCachedTokens());
        assertEquals(130, response.getUsage().getTotalTokens());
    }

    @Test
    void chat_nonStream_cachedAbsent_keepsLegacySemantics() throws Exception {
        // 老响应无 prompt_tokens_details → cachedTokens=null、promptTokens 原值（老口径逐分一致）
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"choices":[{"message":{"content":"hi"}}],"model":"deepseek-chat",
                         "usage":{"prompt_tokens":100,"completion_tokens":7,"total_tokens":107}}
                        """));

        LlmRequest request = LlmRequest.builder()
                .model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .build();

        LlmResponse response = provider.chat(request);
        assertEquals(100, response.getUsage().getPromptTokens());
        assertNull(response.getUsage().getCachedTokens());
    }

    @Test
    void chatStream_cachedDetails_netPromptAndCachedLeg() throws Exception {
        // 流末 usage chunk 带 prompt_tokens_details → side-channel 同口径归一
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"content":"Hi"}}]}

                        data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":8,"total_tokens":138,"prompt_tokens_details":{"cached_tokens":40}}}

                        data: [DONE]

                        """));

        LlmRequest request = LlmRequest.builder()
                .model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .stream(true)
                .build();

        AtomicReference<TokenUsage> captured = new AtomicReference<>();
        provider.chatStream(request, captured::set)
                .map(StreamEvent::getContent)
                .collectList()
                .block();

        assertNotNull(captured.get());
        assertEquals(60, captured.get().getPromptTokens());
        assertEquals(40L, captured.get().getCachedTokens());
    }

    // ==================== 修复IX-1 A3：声明制思考参数 ====================

    /** 档位响应体统一构造（非流式，返回原始请求 body）。 */
    private String chatAndCaptureBody(OpenAICompatibleProvider p, LlmRequest request) throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{}}"));
        p.chat(request);
        return server.takeRequest().getBody().readUtf8();
    }

    private LlmRequest reqWithLevel(ThinkingLevel level) {
        return LlmRequest.builder()
                .model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("Hi").build()))
                .thinkingLevel(level)
                .build();
    }

    @Test
    void chat_noThinkingSpec_shouldNeverSendThinkingParams() throws Exception {
        // 锁死「未声明=零思考参数」：带档位也不发（现状兼容基线）
        String body = chatAndCaptureBody(provider, reqWithLevel(ThinkingLevel.DEEP));
        assertFalse(body.contains("\"thinking\""), "未声明不得发 thinking，实际=" + body);
        assertFalse(body.contains("reasoning_effort"), "未声明不得发 reasoning_effort，实际=" + body);
    }

    @Test
    void chat_toggleStyle_shouldMapOffAndOn() throws Exception {
        OpenAICompatibleProvider p = new OpenAICompatibleProvider("test",
                server.url("/v1/chat/completions").toString(), "key", List.of("deepseek-chat"), mapper,
                null, "GLOBAL", ThinkingSpec.parse(mapper, "{\"thinking\":{\"style\":\"toggle\"}}"));

        String off = chatAndCaptureBody(p, reqWithLevel(ThinkingLevel.OFF));
        assertTrue(off.contains("\"thinking\":{\"type\":\"disabled\"}"), "OFF→disabled，实际=" + off);

        String on = chatAndCaptureBody(p, reqWithLevel(ThinkingLevel.STANDARD));
        assertTrue(on.contains("\"thinking\":{\"type\":\"enabled\"}"), "STANDARD→enabled，实际=" + on);

        // toggle 无深度态：DEEP 兜底同 enabled（防 localStorage 残留档 400）
        String deep = chatAndCaptureBody(p, reqWithLevel(ThinkingLevel.DEEP));
        assertTrue(deep.contains("\"thinking\":{\"type\":\"enabled\"}"), "DEEP→enabled（兜底），实际=" + deep);
        assertFalse(deep.contains("reasoning_effort"), "toggle 风格不得混发 effort，实际=" + deep);
    }

    @Test
    void chat_effortStyle_shouldMapThreeLevels() throws Exception {
        OpenAICompatibleProvider p = new OpenAICompatibleProvider("test",
                server.url("/v1/chat/completions").toString(), "key", List.of("deepseek-chat"), mapper,
                null, "GLOBAL", ThinkingSpec.parse(mapper, "{\"thinking\":{\"style\":\"effort\"}}"));

        assertEquals(true, chatAndCaptureBody(p, reqWithLevel(ThinkingLevel.OFF)).contains("\"reasoning_effort\":\"low\""));
        assertEquals(true, chatAndCaptureBody(p, reqWithLevel(ThinkingLevel.STANDARD)).contains("\"reasoning_effort\":\"medium\""));
        assertEquals(true, chatAndCaptureBody(p, reqWithLevel(ThinkingLevel.DEEP)).contains("\"reasoning_effort\":\"high\""));
    }

    @Test
    void chat_specModelsWhitelist_shouldFilterOutUnlistedModel() throws Exception {
        OpenAICompatibleProvider p = new OpenAICompatibleProvider("test",
                server.url("/v1/chat/completions").toString(), "key",
                List.of("deepseek-chat", "glm-5.3"), mapper,
                null, "GLOBAL", ThinkingSpec.parse(mapper, "{\"thinking\":{\"style\":\"toggle\",\"models\":[\"glm-5.3\"]}}"));

        String body = chatAndCaptureBody(p, reqWithLevel(ThinkingLevel.STANDARD));
        assertFalse(body.contains("\"thinking\""), "白名单外模型不得发参数，实际=" + body);
    }

    @Test
    void chat_disableThinking_withSpec_shouldMapToOff() throws Exception {
        // 老字段兼容：内部 JSON 蒸馏调用 disableThinking=true，在已声明 provider 上也应发 disabled
        OpenAICompatibleProvider p = new OpenAICompatibleProvider("test",
                server.url("/v1/chat/completions").toString(), "key", List.of("deepseek-chat"), mapper,
                null, "GLOBAL", ThinkingSpec.parse(mapper, "{\"thinking\":{\"style\":\"toggle\"}}"));
        String body = chatAndCaptureBody(p, LlmRequest.builder()
                .model("deepseek-chat")
                .messages(List.of(LlmMessage.builder().role("user").content("总结").build()))
                .disableThinking(true)
                .build());
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"), "disableThinking 须映射 OFF，实际=" + body);
    }
}
