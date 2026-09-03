package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.knowledge.dto.KnowledgeConnectorRequest;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeConnector;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeConnectorMapper;
import com.superprogrammer.llm.service.AesEncryptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP6 Step1：连接器 CRUD——凭证密文落库（无明文）/解密回读/权限 403/类型结构校验。
 * AES 用真实实例（默认密钥 dev 态即可）验证端到端加解密，非 mock 自证。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeConnectorServiceTest {

    @Mock
    private KnowledgeConnectorMapper connectorMapper;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    private KnowledgeConnectorService service;

    @BeforeEach
    void setUp() {
        AesEncryptService aes = new AesEncryptService();
        aes.setSecret("unit-test-secret-0123456789ab");
        service = new KnowledgeConnectorService(connectorMapper, knowledgeBaseMapper,
                knowledgeBaseService, aes, new ObjectMapper());
    }

    private KnowledgeBase kb(Long id, Long owner) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setCreatedBy(owner);
        return kb;
    }

    private KnowledgeConnectorRequest s3Request() {
        KnowledgeConnectorRequest request = new KnowledgeConnectorRequest();
        request.setName("团队网盘");
        request.setType("S3");
        request.setConfig(Map.of(
                "endpoint", "https://s3.example.com",
                "bucket", "docs",
                "prefix", "wiki/",
                "accessKey", "AKIAEXAMPLE",
                "secretKey", "topSecretKeyValue"));
        return request;
    }

    @Test
    void create_configStoredAsCipher_noPlaintextAnywhere() {
        when(knowledgeBaseMapper.selectById(5L)).thenReturn(kb(5L, 7L));
        when(knowledgeBaseService.isOwnerOrAdmin(any(), any(), anyBoolean())).thenReturn(true);
        when(connectorMapper.insert(any(KnowledgeConnector.class))).thenReturn(1);

        service.create(5L, s3Request(), 7L, false);

        ArgumentCaptor<KnowledgeConnector> captor = ArgumentCaptor.forClass(KnowledgeConnector.class);
        verify(connectorMapper).insert(captor.capture());
        String cipher = captor.getValue().getConfigCipher();
        // 密文非明文：不含任何敏感原文字段值，且整体不等于明文 JSON
        assertFalse(cipher.contains("topSecretKeyValue"), "密文不得含 secretKey 明文");
        assertFalse(cipher.contains("AKIAEXAMPLE"), "密文不得含 accessKey 明文");
        assertFalse(cipher.contains("s3.example.com"), "密文不得含 endpoint 明文");

        // 解密回读（worker Step3 消费口径）
        Map<String, Object> decrypted = service.decryptConfig(captor.getValue());
        assertEquals("https://s3.example.com", decrypted.get("endpoint"));
        assertEquals("docs", decrypted.get("bucket"));
        assertEquals("topSecretKeyValue", decrypted.get("secretKey"));
        // 默认值口径
        assertEquals("ENABLED", captor.getValue().getStatus());
        assertEquals("0 0 4 * * *", captor.getValue().getScheduleCron());
        assertFalse(captor.getValue().getSyncOnSourceDelete());
    }

    @Test
    void create_nonOwnerOrAdmin_forbidden() {
        when(knowledgeBaseMapper.selectById(5L)).thenReturn(kb(5L, 7L));
        when(knowledgeBaseService.isOwnerOrAdmin(any(), any(), anyBoolean())).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(5L, s3Request(), 9L, false));
        assertEquals(403, e.getCode());
        verify(connectorMapper, org.mockito.Mockito.never()).insert(any(KnowledgeConnector.class));
    }

    @Test
    void create_invalidConfig_unprocessable() {
        when(knowledgeBaseMapper.selectById(5L)).thenReturn(kb(5L, 7L));
        when(knowledgeBaseService.isOwnerOrAdmin(any(), any(), anyBoolean())).thenReturn(true);

        // S3 缺 bucket
        KnowledgeConnectorRequest noBucket = s3Request();
        noBucket.setConfig(Map.of("endpoint", "https://s3.example.com",
                "accessKey", "a", "secretKey", "b"));
        assertThrows(BusinessException.class, () -> service.create(5L, noBucket, 7L, false));

        // URL_SITE 种子非 http(s)
        KnowledgeConnectorRequest badUrl = s3Request();
        badUrl.setType("URL_SITE");
        badUrl.setConfig(Map.of("seedUrl", "ftp://example.com"));
        assertThrows(BusinessException.class, () -> service.create(5L, badUrl, 7L, false));

        // 类型非法
        KnowledgeConnectorRequest badType = s3Request();
        badType.setType("FTP");
        assertThrows(BusinessException.class, () -> service.create(5L, badType, 7L, false));

        // cron 非法（Spring 六段）
        KnowledgeConnectorRequest badCron = s3Request();
        badCron.setScheduleCron("99 99 99 * * *");
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(5L, badCron, 7L, false));
        assertEquals(422, e.getCode());
    }

    @Test
    void update_typeChange_rejected_configReseEncrypted() {
        KnowledgeConnector existing = new KnowledgeConnector();
        existing.setId(11L);
        existing.setKbId(5L);
        existing.setType("S3");
        existing.setName("旧名");
        existing.setConfigCipher("old-cipher");
        existing.setScheduleCron("0 0 5 * * *");
        when(connectorMapper.selectById(11L)).thenReturn(existing);
        when(knowledgeBaseMapper.selectById(5L)).thenReturn(kb(5L, 7L));
        when(knowledgeBaseService.isOwnerOrAdmin(any(), any(), anyBoolean())).thenReturn(true);

        // 类型不可变
        KnowledgeConnectorRequest changeType = new KnowledgeConnectorRequest();
        changeType.setName("n");
        changeType.setType("WEBDAV");
        assertThrows(BusinessException.class, () -> service.update(11L, changeType, 7L, false));

        // config=null 保留原密文；其余字段可改
        KnowledgeConnectorRequest patch = new KnowledgeConnectorRequest();
        patch.setName("新名");
        patch.setSyncOnSourceDelete(true);
        service.update(11L, patch, 7L, false);
        assertEquals("old-cipher", existing.getConfigCipher());
        assertEquals("新名", existing.getName());
        assertTrue(existing.getSyncOnSourceDelete());
        verify(connectorMapper).updateById(existing);
    }
}
