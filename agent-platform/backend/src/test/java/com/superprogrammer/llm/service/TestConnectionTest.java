package com.superprogrammer.llm.service;

import com.superprogrammer.llm.config.LlmConfig;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.llm.dto.TestConnectionResult;
import com.superprogrammer.llm.dto.TokenUsage;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.mapper.EmbeddingModelVersionMapper;
import com.superprogrammer.llm.mapper.LlmProviderMapper;
import com.superprogrammer.llm.provider.LlmProviderInterface;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TestConnectionTest {

    @Mock
    private LlmProviderMapper mapper;

    @Mock
    private AesEncryptService aesEncryptService;

    @Mock
    private LlmConfig llmConfig;

    @Mock
    private LlmProviderInterface provider;

    @Mock
    private EmbeddingModelVersionMapper embeddingModelVersionMapper;

    private LlmProviderService service;

    @BeforeEach
    void setUp() {
        service = new LlmProviderService(mapper, aesEncryptService, llmConfig, embeddingModelVersionMapper);
    }

    private LlmProviderEntity buildEntity() {
        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setId(1L);
        entity.setName("deepseek");
        entity.setApiEndpoint("https://api.deepseek.com/v1");
        entity.setApiKeyEnc("encrypted-key");
        entity.setModels("[\"deepseek-chat\"]");
        entity.setStatus("ACTIVE");
        return entity;
    }

    @Test
    void testConnection_success() {
        LlmProviderEntity entity = buildEntity();
        when(mapper.selectById(1L)).thenReturn(entity);
        when(aesEncryptService.decrypt("encrypted-key")).thenReturn("sk-test-key");
        when(llmConfig.createProvider(entity, "sk-test-key")).thenReturn(provider);

        LlmResponse mockResponse = LlmResponse.builder()
                .content("Hi")
                .model("deepseek-chat")
                .duration(150L)
                .usage(TokenUsage.builder().promptTokens(5).completionTokens(1).totalTokens(6).build())
                .build();
        when(provider.chat(any())).thenReturn(mockResponse);

        TestConnectionResult result = service.testConnection(1L);

        assertTrue(result.isSuccess());
        assertEquals("连接成功", result.getMessage());
        assertEquals("deepseek-chat", result.getModel());
        assertEquals(150L, result.getDurationMs());
    }

    @Test
    void testConnection_providerNotFound() {
        when(mapper.selectById(999L)).thenReturn(null);

        assertThrows(com.superprogrammer.common.exception.BusinessException.class,
                () -> service.testConnection(999L));
    }

    @Test
    void testConnection_noModels() {
        LlmProviderEntity entity = buildEntity();
        entity.setModels(null);
        when(mapper.selectById(1L)).thenReturn(entity);

        TestConnectionResult result = service.testConnection(1L);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("未配置模型列表"));
    }

    @Test
    void testConnection_noEndpoint() {
        LlmProviderEntity entity = buildEntity();
        entity.setApiEndpoint("");
        when(mapper.selectById(1L)).thenReturn(entity);

        TestConnectionResult result = service.testConnection(1L);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("未配置API端点"));
    }

    @Test
    void testConnection_apiError() {
        LlmProviderEntity entity = buildEntity();
        when(mapper.selectById(1L)).thenReturn(entity);
        when(aesEncryptService.decrypt("encrypted-key")).thenReturn("sk-test-key");
        when(llmConfig.createProvider(entity, "sk-test-key")).thenReturn(provider);
        when(provider.chat(any())).thenThrow(new RuntimeException("401 Unauthorized"));

        TestConnectionResult result = service.testConnection(1L);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("401 Unauthorized"));
    }

    @Test
    void testConnection_withEntityAndKey() {
        LlmProviderEntity entity = buildEntity();
        when(llmConfig.createProvider(eq(entity), eq("sk-direct-key"))).thenReturn(provider);

        LlmResponse mockResponse = LlmResponse.builder()
                .content("Hi")
                .model("deepseek-chat")
                .duration(200L)
                .usage(TokenUsage.builder().promptTokens(5).completionTokens(1).totalTokens(6).build())
                .build();
        when(provider.chat(any())).thenReturn(mockResponse);

        TestConnectionResult result = service.testConnection(entity, "sk-direct-key");

        assertTrue(result.isSuccess());
        assertEquals(200L, result.getDurationMs());
    }
}
