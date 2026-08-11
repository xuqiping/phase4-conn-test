package com.superprogrammer.llm.service;

import com.superprogrammer.billing.service.LlmBillingService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.llm.config.LlmConfig;
import com.superprogrammer.llm.dto.LlmProviderExportItem;
import com.superprogrammer.llm.dto.ProviderImportResult;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 供应商导入（问题 10x-2）单测：upsert by name、apiKey 空保留原值、非法行跳过、size 超限。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmProviderImportTest {

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
        when(aesEncryptService.encrypt(any())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");
    }

    private LlmProviderExportItem item(String name, String endpoint, String key) {
        LlmProviderExportItem it = new LlmProviderExportItem();
        it.setName(name);
        it.setApiEndpoint(endpoint);
        it.setApiKey(key);
        it.setCategory("CHAT");
        it.setProtocol("OPENAI_COMPATIBLE");
        return it;
    }

    @Test
    void import_newName_shouldInsertAndCountCreated() {
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.selectOne(any())).thenReturn(null); // getByName 不存在
        when(mapper.insert(any())).thenReturn(1);

        ProviderImportResult r = service.importAll(List.of(item("newprov", "https://a.com/v1", "sk-1")));

        assertEquals(1, r.getCreated());
        assertEquals(0, r.getUpdated());
        assertEquals(0, r.getFailed());
        // 新建时 key 须加密落库
        verify(aesEncryptService).encrypt("sk-1");
        verify(mapper).insert(any());
    }

    @Test
    void import_existingName_shouldUpdateFields() {
        LlmProviderEntity existing = new LlmProviderEntity();
        existing.setId(5L);
        existing.setName("dup");
        existing.setApiKeyEnc("OLD-ENC");
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.updateById(any())).thenReturn(1);

        ProviderImportResult r = service.importAll(List.of(item("dup", "https://b.com/v1", "sk-new")));

        assertEquals(0, r.getCreated());
        assertEquals(1, r.getUpdated());
        verify(aesEncryptService).encrypt("sk-new");
        verify(mapper).updateById(argThat(e -> "https://b.com/v1".equals(e.getApiEndpoint())));
    }

    @Test
    void import_existingName_emptyApiKey_shouldKeepOldKey() {
        // 导入项 apiKey 为空时，不得覆盖/清空已存在 provider 的 key
        LlmProviderEntity existing = new LlmProviderEntity();
        existing.setId(5L);
        existing.setName("dup");
        existing.setApiKeyEnc("OLD-ENC");
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.updateById(any())).thenReturn(1);

        LlmProviderExportItem it = item("dup", "https://b.com/v1", null);
        ProviderImportResult r = service.importAll(List.of(it));

        assertEquals(1, r.getUpdated());
        verify(aesEncryptService, never()).encrypt(any());
        verify(mapper).updateById(argThat(e -> "OLD-ENC".equals(e.getApiKeyEnc())));
    }

    @Test
    void import_blankName_shouldSkipAndCountFailed() {
        when(mapper.selectList(any())).thenReturn(List.of());
        LlmProviderExportItem bad = item("", "https://a.com/v1", "sk");

        ProviderImportResult r = service.importAll(List.of(bad));

        assertEquals(0, r.getCreated());
        assertEquals(1, r.getFailed());
        assertTrue(r.getErrors().get(0).contains("name"));
        verify(mapper, never()).insert(any());
    }

    @Test
    void import_nonHttpEndpoint_shouldSkip() {
        LlmProviderExportItem bad = item("x", "ftp://bad.com", "sk");
        ProviderImportResult r = service.importAll(List.of(bad));
        assertEquals(1, r.getFailed());
        verify(mapper, never()).insert(any());
    }

    @Test
    void import_mixedBatch_shouldPartiallySucceed() {
        // 1 新增 + 1 更新 + 1 非法，互不干扰
        when(mapper.selectOne(any())).thenReturn(null); // 两行都不存在
        when(mapper.insert(any())).thenReturn(1);
        LlmProviderExportItem ok1 = item("n1", "https://a.com/v1", "sk1");
        LlmProviderExportItem bad = item("n2", "not-a-url", "sk2");
        LlmProviderExportItem ok2 = item("n3", "https://c.com/v1", null);

        ProviderImportResult r = service.importAll(List.of(ok1, bad, ok2));

        assertEquals(2, r.getCreated());
        assertEquals(0, r.getUpdated());
        assertEquals(1, r.getFailed());
    }

    @Test
    void import_overSizeLimit_shouldThrowBadRequest() {
        List<LlmProviderExportItem> big = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            big.add(item("p" + i, "https://a.com/v1", "sk"));
        }
        BusinessException e = assertThrows(BusinessException.class, () -> service.importAll(big));
        assertEquals(400, e.getCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    void import_shouldReloadConfigAfterwards() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);
        service.importAll(List.of(item("n", "https://a.com/v1", "sk")));
        verify(llmConfig).reload();
    }

    @Test
    void import_nullList_shouldReturnEmptyResult() {
        ProviderImportResult r = service.importAll(null);
        assertEquals(0, r.getCreated());
        verify(llmConfig).reload();
    }
}
