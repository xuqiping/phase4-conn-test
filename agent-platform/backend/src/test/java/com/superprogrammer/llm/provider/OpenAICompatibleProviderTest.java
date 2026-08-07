package com.superprogrammer.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.dto.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.List;

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
    void supports_shouldMatchModelName() {
        assertTrue(provider.supports("deepseek-chat"));
        assertFalse(provider.supports("gpt-4"));
        assertFalse(provider.supports("any-model"));
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
}
