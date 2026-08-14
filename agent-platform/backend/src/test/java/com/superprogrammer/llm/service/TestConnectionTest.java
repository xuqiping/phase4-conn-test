package com.superprogrammer.llm.service;

import com.superprogrammer.billing.service.LlmBillingService;
import com.superprogrammer.llm.config.LlmConfig;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.llm.dto.TestConnectionResult;
import com.superprogrammer.llm.dto.TokenUsage;
import com.superprogrammer.llm.dto.RerankResult;
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

    @Mock
    private LlmBillingService billingService;

    private LlmProviderService service;

    @BeforeEach
    void setUp() {
        service = new LlmProviderService(mapper, aesEncryptService, llmConfig, embeddingModelVersionMapper, billingService);
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

    @Test
    void testConnection_imageCategory_shortCircuitsWithoutRequest() {
        // FR-004：IMAGE 是生图预留位，点「测试」不发请求直接给「未接入」话术
        LlmProviderEntity entity = buildEntity();
        entity.setCategory(LlmProviderService.CATEGORY_IMAGE);
        when(mapper.selectById(1L)).thenReturn(entity);

        TestConnectionResult result = service.testConnection(1L);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("生图"), "话术须说明 IMAGE 未接入: " + result.getMessage());
        verify(llmConfig, never()).createProvider(any(), any());
    }

    @Test
    void testEmbedding_anthropicProtocol_returnsClearMessage() {
        // FR-004：ANTHROPIC+EMBEDDING 组合不成立，明确话术而非上游错误
        LlmProviderEntity entity = buildEntity();
        entity.setName("claude-embed");
        entity.setProtocol("ANTHROPIC");
        entity.setCategory(LlmProviderService.CATEGORY_EMBEDDING);
        entity.setModels("[\"some-embed-model\"]");
        when(mapper.selectById(1L)).thenReturn(entity);

        TestConnectionResult result = service.testEmbedding(1L);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("ANTHROPIC"), "话术须点名协议: " + result.getMessage());
        verify(llmConfig, never()).createProvider(any(), any());
    }

    @Test
    void testEmbedding_anthropicByNameInference_returnsClearMessage() {
        // protocol 缺省时沿用 name=claude 推断（与 LlmConfig 口径一致）
        LlmProviderEntity entity = buildEntity();
        entity.setName("claude");
        entity.setProtocol(null);
        entity.setCategory(LlmProviderService.CATEGORY_EMBEDDING);
        entity.setModels("[\"some-embed-model\"]");
        when(mapper.selectById(1L)).thenReturn(entity);

        TestConnectionResult result = service.testEmbedding(1L);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("ANTHROPIC"));
        verify(llmConfig, never()).createProvider(any(), any());
    }

    @Test
    void testRerank_callsSelectedProviderAndReturnsCount() {
        LlmProviderEntity entity = buildEntity();
        entity.setCategory(LlmProviderService.CATEGORY_RERANK);
        entity.setModels("[\"configured-rerank-model\"]");
        when(mapper.selectById(1L)).thenReturn(entity);
        when(aesEncryptService.decrypt("encrypted-key")).thenReturn("sk-test-key");
        when(llmConfig.createProvider(entity, "sk-test-key")).thenReturn(provider);
        when(provider.rerank(any())).thenReturn(RerankResult.builder()
                .model("configured-rerank-model").duration(88L)
                .usage(TokenUsage.builder().promptTokens(20).completionTokens(0).totalTokens(20).build())
                .items(java.util.List.of(
                        RerankResult.Item.builder().index(0).score(0.9).build(),
                        RerankResult.Item.builder().index(2).score(0.8).build()))
                .build());

        TestConnectionResult result = service.testRerank(1L);

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("2"));
        assertEquals("configured-rerank-model", result.getModel());
        assertEquals(88L, result.getDurationMs());
        verify(billingService).onSuccess(any(), eq(1L), eq("GLOBAL"),
                eq("configured-rerank-model"), eq("RERANK"), eq(20), eq(0));
    }

    @Test
    void testRerank_failsWhenIrrelevantDocumentRanksAhead() {
        LlmProviderEntity entity = buildEntity();
        entity.setCategory(LlmProviderService.CATEGORY_RERANK);
        entity.setModels("[\"configured-rerank-model\"]");
        when(mapper.selectById(1L)).thenReturn(entity);
        when(aesEncryptService.decrypt("encrypted-key")).thenReturn("sk-test-key");
        when(llmConfig.createProvider(entity, "sk-test-key")).thenReturn(provider);
        when(provider.rerank(any())).thenReturn(RerankResult.builder()
                .model("configured-rerank-model").duration(20L)
                .items(java.util.List.of(
                        RerankResult.Item.builder().index(1).score(0.9).build(),
                        RerankResult.Item.builder().index(0).score(0.8).build()))
                .build());

        TestConnectionResult result = service.testRerank(1L);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("语义排序"));
        verifyNoInteractions(billingService);
    }
}
