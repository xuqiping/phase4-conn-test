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
        String baseUrl = server.url("").toString();
        provider = new OpenAICompatibleProvider("test", baseUrl, "test-key", List.of("deepseek-chat"), mapper);
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
}
