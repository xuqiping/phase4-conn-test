package com.superprogrammer.llm.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.provider.ClaudeProvider;
import com.superprogrammer.llm.provider.LlmProviderInterface;
import com.superprogrammer.llm.provider.OpenAICompatibleProvider;
import com.superprogrammer.llm.service.LlmProviderService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class LlmConfigTest {

    private final LlmConfig config = new LlmConfig(mock(LlmProviderService.class), new ObjectMapper());

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
}
