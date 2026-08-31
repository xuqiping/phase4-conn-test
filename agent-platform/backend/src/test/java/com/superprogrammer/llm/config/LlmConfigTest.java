package com.superprogrammer.llm.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.provider.ClaudeProvider;
import com.superprogrammer.llm.provider.LlmProviderInterface;
import com.superprogrammer.llm.provider.OpenAICompatibleProvider;
import com.superprogrammer.llm.service.LlmProviderService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class LlmConfigTest {

    private final LlmConfig config = new LlmConfig(mock(LlmProviderService.class), new ObjectMapper(),
            new com.superprogrammer.llm.config.LlmThinkingProperties());

    @Test
    void createProvider_shouldUseAnthropicProtocolIndependentOfProviderName() {
        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setName("glm");
        entity.setProtocol("ANTHROPIC");
        entity.setApiEndpoint("https://open.bigmodel.cn/api/anthropic");
        entity.setModels("[\"glm-5.1\"]");

        LlmProviderInterface provider = config.createProvider(entity, "test-key");

        assertInstanceOf(ClaudeProvider.class, provider);
    }

    @Test
    void createProvider_shouldDefaultToOpenAiCompatibleProtocol() {
        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setName("glm");
        entity.setApiEndpoint("https://open.bigmodel.cn/api/paas/v4");
        entity.setModels("[\"glm-5.1\"]");

        LlmProviderInterface provider = config.createProvider(entity, "test-key");

        assertInstanceOf(OpenAICompatibleProvider.class, provider);
    }

    @Test
    void initProviders_shouldKeepRerankOutOfChatRegistry() {
        LlmProviderService service = mock(LlmProviderService.class);
        LlmConfig isolatedConfig = new LlmConfig(service, new ObjectMapper(),
                new com.superprogrammer.llm.config.LlmThinkingProperties());
        LlmProviderEntity rerank = new LlmProviderEntity();
        rerank.setId(9L);
        rerank.setName("rerank-provider");
        rerank.setCategory(LlmProviderService.CATEGORY_RERANK);
        rerank.setApiEndpoint("https://example.test/v1/reranks");
        rerank.setModels("[\"configured-rerank-model\"]");
        when(service.listActive()).thenReturn(List.of(rerank));
        when(service.getDecryptedApiKey(9L)).thenReturn("key");

        isolatedConfig.initProviders();

        assertEquals(0, isolatedConfig.getProviders().size());
        assertEquals(0, isolatedConfig.getEmbedProviders().size());
        assertEquals(1, isolatedConfig.getRerankProviders().size());
    }
}
