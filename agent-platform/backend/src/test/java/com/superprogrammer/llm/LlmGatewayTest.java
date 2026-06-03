package com.superprogrammer.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    private LlmGateway gateway;

    @BeforeEach
    void setUp() {
        when(deepseekProvider.getName()).thenReturn("deepseek");
        lenient().when(deepseekProvider.supports(anyString())).thenReturn(false);
        when(deepseekProvider.supports("deepseek-chat")).thenReturn(true);

        when(openaiProvider.getName()).thenReturn("openai");
        lenient().when(openaiProvider.supports(anyString())).thenReturn(true);

        when(llmConfig.getProviders()).thenReturn(List.of(deepseekProvider, openaiProvider));
        gateway = new LlmGateway(llmConfig, userLlmProviderService, llmProviderService, objectMapper);
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
        LlmGateway emptyGateway = new LlmGateway(llmConfig, userLlmProviderService, llmProviderService, objectMapper);
        LlmRequest request = LlmRequest.builder().model("unknown").build();
        assertThrows(RuntimeException.class, () -> emptyGateway.chat(request));
    }
}
