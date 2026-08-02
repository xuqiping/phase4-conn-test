package com.superprogrammer.llm.service;

import com.superprogrammer.llm.config.LlmConfig;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.mapper.EmbeddingModelVersionMapper;
import com.superprogrammer.llm.mapper.LlmProviderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmProviderServiceTest {

    @Mock
    private LlmProviderMapper mapper;

    @Mock
    private AesEncryptService aesEncryptService;

    @Mock
    private LlmConfig llmConfig;

    @Mock
    private EmbeddingModelVersionMapper embeddingModelVersionMapper;

    private LlmProviderService service;

    @BeforeEach
    void setUp() {
        service = new LlmProviderService(mapper, aesEncryptService, llmConfig, embeddingModelVersionMapper);
    }

    @Test
    void create_shouldEncryptApiKeyAndInsert() {
        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setName("deepseek");
        entity.setApiEndpoint("https://api.deepseek.com/v1");
        entity.setApiKeyEnc("sk-plain-key");

        when(aesEncryptService.encrypt("sk-plain-key")).thenReturn("encrypted-key");
        when(mapper.insert(any(LlmProviderEntity.class))).thenReturn(1);

        service.create(entity);

        verify(aesEncryptService).encrypt("sk-plain-key");
        verify(mapper).insert(argThat(e -> "encrypted-key".equals(e.getApiKeyEnc())));
    }

    @Test
    void listActive_shouldReturnOnlyActiveProviders() {
        LlmProviderEntity e = new LlmProviderEntity();
        e.setName("deepseek");
        e.setStatus("ACTIVE");
        when(mapper.selectList(any())).thenReturn(List.of(e));

        var result = service.listActive();
        assertEquals(1, result.size());
        assertEquals("deepseek", result.get(0).getName());
    }

    @Test
    void getDecryptedApiKey_shouldDecryptStoredKey() {
        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setApiKeyEnc("encrypted-key");
        when(mapper.selectById(1L)).thenReturn(entity);
        when(aesEncryptService.decrypt("encrypted-key")).thenReturn("sk-plain-key");

        String key = service.getDecryptedApiKey(1L);
        assertEquals("sk-plain-key", key);
    }
}
