package com.superprogrammer.llm.service;

import com.superprogrammer.billing.service.LlmBillingService;
import com.superprogrammer.llm.config.LlmConfig;
import com.superprogrammer.llm.dto.LlmProviderExportItem;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.mapper.EmbeddingModelVersionMapper;
import com.superprogrammer.llm.mapper.LlmProviderMapper;
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
import static org.mockito.Mockito.*;

/**
 * 供应商导出（问题 10x-2）单测：解密 key 填明文、解密失败不中断、空列表安全返回。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmProviderExportTest {

    @Mock
    private LlmProviderMapper mapper;
    @Mock
    private AesEncryptService aesEncryptService;
    @Mock
    private LlmConfig llmConfig;
    @Mock
    private EmbeddingModelVersionMapper embeddingModelVersionMapper;
    @Mock
    private LlmBillingService billingService;

    private LlmProviderService service;

    @BeforeEach
    void setUp() {
        service = new LlmProviderService(mapper, aesEncryptService, llmConfig, embeddingModelVersionMapper, billingService);
    }

    private LlmProviderEntity entity(long id, String name, String encKey) {
        LlmProviderEntity e = new LlmProviderEntity();
        e.setId(id);
        e.setName(name);
        e.setDisplayName(name.toUpperCase());
        e.setProtocol("OPENAI_COMPATIBLE");
        e.setApiEndpoint("https://api.x.com/v1/chat/completions");
        e.setApiKeyEnc(encKey);
        e.setModels("[\"gpt-4o\"]");
        e.setCategory("CHAT");
        e.setStatus("ACTIVE");
        e.setSortOrder((int) id);
        e.setDeleted(0);
        return e;
    }

    @Test
    void exportAll_shouldDecryptKeyToPlaintext() {
        when(mapper.selectList(any())).thenReturn(List.of(entity(1, "openai", "ENC1")));
        when(aesEncryptService.decrypt("ENC1")).thenReturn("sk-plain-openai");

        List<LlmProviderExportItem> out = service.exportAll();

        assertEquals(1, out.size());
        assertEquals("openai", out.get(0).getName());
        assertEquals("sk-plain-openai", out.get(0).getApiKey());
    }

    @Test
    void exportAll_decryptFailure_shouldKeepGoingWithNullKey() {
        // 解密失败的条目不中断整体导出
        when(mapper.selectList(any())).thenReturn(List.of(
                entity(1, "ok", "ENC1"),
                entity(2, "bad", "ENC2")));
        when(aesEncryptService.decrypt("ENC1")).thenReturn("sk-ok");
        when(aesEncryptService.decrypt("ENC2")).thenThrow(new RuntimeException("解密失败"));

        List<LlmProviderExportItem> out = service.exportAll();

        assertEquals(2, out.size());
        assertEquals("sk-ok", out.get(0).getApiKey());
        assertNull(out.get(1).getApiKey(), "解密失败的条目 key 应为 null");
        assertEquals("bad", out.get(1).getName(), "条目本身仍导出");
    }

    @Test
    void exportAll_nullEncKey_shouldHaveNullApiKey() {
        // 没设过 key 的 provider，apiKey 为 null，且不调用 decrypt
        when(mapper.selectList(any())).thenReturn(List.of(entity(1, "nokey", null)));

        List<LlmProviderExportItem> out = service.exportAll();

        assertEquals(1, out.size());
        assertNull(out.get(0).getApiKey());
        verify(aesEncryptService, never()).decrypt(any());
    }

    @Test
    void exportAll_emptyList_shouldReturnEmpty() {
        when(mapper.selectList(any())).thenReturn(List.of());
        assertTrue(service.exportAll().isEmpty());
    }

    @Test
    void exportAll_shouldPreserveAllFields() {
        LlmProviderEntity e = entity(1, "claude", "ENC");
        e.setCategory("EMBEDDING");
        e.setConfig("{\"dim\":1536}");
        e.setStatus("INACTIVE");
        when(mapper.selectList(any())).thenReturn(List.of(e));
        when(aesEncryptService.decrypt("ENC")).thenReturn("sk-x");

        LlmProviderExportItem item = service.exportAll().get(0);

        assertEquals("OPENAI_COMPATIBLE", item.getProtocol());
        assertEquals("https://api.x.com/v1/chat/completions", item.getApiEndpoint());
        assertEquals("[\"gpt-4o\"]", item.getModels());
        assertEquals("{\"dim\":1536}", item.getConfig());
        assertEquals("EMBEDDING", item.getCategory());
        assertEquals("INACTIVE", item.getStatus());
        assertEquals(1, item.getSortOrder());
    }
}
