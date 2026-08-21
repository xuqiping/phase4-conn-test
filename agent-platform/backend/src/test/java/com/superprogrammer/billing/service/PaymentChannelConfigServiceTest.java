package com.superprogrammer.billing.service;

import com.superprogrammer.billing.dto.PaymentChannelConfigVO;
import com.superprogrammer.billing.entity.PaymentChannelConfigEntity;
import com.superprogrammer.billing.mapper.PaymentChannelConfigMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.llm.service.AesEncryptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付渠道网页配置单测（7x 追加）。
 * 用真 AesEncryptService（测试密钥）+ mock mapper：断言落库串不含明文、tails 脱敏规则、merge 语义。
 */
@ExtendWith(MockitoExtension.class)
class PaymentChannelConfigServiceTest {

    @Mock private PaymentChannelConfigMapper configMapper;

    private PaymentChannelConfigService service;
    private final AesEncryptService aes = new AesEncryptService();

    @BeforeEach
    void setUp() {
        aes.setSecret("unit-test-payment-config-secret-32B");
        service = new PaymentChannelConfigService(configMapper, aes, new ObjectMapper());
        // 单测不经 Spring，@Value 兜底字段手动灌
        ReflectionTestUtils.setField(service, "envAlipayAppId", "env-app-id");
        ReflectionTestUtils.setField(service, "envAlipayPrivateKey", "env-private-key");
        ReflectionTestUtils.setField(service, "envAlipayPublicKey", "env-public-key");
    }

    private Map<String, String> alipayFull() {
        return Map.of(
                "appId", "2021000123456789",
                "privateKey", "MIIEvQIBADANBgkqhkiG9w0BAQEFAASC",
                "alipayPublicKey", "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A");
    }

    @Test
    void 保存_落库为密文不含明文_tails按尾4脱敏() {
        when(configMapper.selectByChannel("ALIPAY")).thenReturn(null);

        service.save("ALIPAY", alipayFull());

        ArgumentCaptor<PaymentChannelConfigEntity> cap = ArgumentCaptor.forClass(PaymentChannelConfigEntity.class);
        verify(configMapper).insert(cap.capture());
        PaymentChannelConfigEntity e = cap.getValue();
        // 密文不含任何明文片段
        assertFalse(e.getConfigEncrypted().contains("2021000123456789"));
        assertFalse(e.getConfigEncrypted().contains("MIIEvQ"));
        // tails：尾 4 保留
        assertTrue(e.getConfigTails().contains("****6789"));
        assertFalse(e.getConfigTails().contains("2021000123456789"));
    }

    @Test
    void 脱敏_短值全遮不泄露() {
        assertEquals("****", PaymentChannelConfigService.mask("abc"));
        assertEquals("****", PaymentChannelConfigService.mask("abcd"));
        assertEquals("****bcde", PaymentChannelConfigService.mask("12345abcde"));
        assertEquals("", PaymentChannelConfigService.mask(""));
    }

    @Test
    void 缺必填键_400() {
        when(configMapper.selectByChannel("ALIPAY")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.save("ALIPAY", Map.of("appId", "2021000123456789")));
        assertTrue(ex.getMessage().contains("缺少必填配置键"));
        verify(configMapper, never()).insert(any());
    }

    @Test
    void 未知键_400_防任意键注入() {
        when(configMapper.selectByChannel("ALIPAY")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.save("ALIPAY", Map.of("appId", "x", "privateKey", "y", "alipayPublicKey", "z", "evil", "v")));
        assertTrue(ex.getMessage().contains("不支持的配置键"));
        verify(configMapper, never()).insert(any());
    }

    @Test
    void 未知渠道_400() {
        assertThrows(BusinessException.class, () -> service.save("MOCK", Map.of("appId", "x")));
        verify(configMapper, never()).insert(any());
    }

    @Test
    void merge_留空字段保持原值_非空覆盖() {
        // 已有完整配置
        PaymentChannelConfigEntity existing = new PaymentChannelConfigEntity();
        existing.setId(1L);
        existing.setChannel("ALIPAY");
        existing.setConfigEncrypted(aes.encrypt(
                "{\"appId\":\"old-app\",\"privateKey\":\"old-key\",\"alipayPublicKey\":\"old-pub\"}"));
        when(configMapper.selectByChannel("ALIPAY")).thenReturn(existing);

        // 只改 privateKey，appId 传空串=保持
        service.save("ALIPAY", Map.of("appId", "", "privateKey", "new-key-12345678", "alipayPublicKey", ""));

        ArgumentCaptor<PaymentChannelConfigEntity> cap = ArgumentCaptor.forClass(PaymentChannelConfigEntity.class);
        verify(configMapper).updateById(cap.capture());
        Map<String, String> merged = service.getDecrypted("ALIPAY");  // DB 行被 mock 返回 existing（已就地更新）
        assertEquals("old-app", merged.get("appId"));                 // 保持原值
        assertEquals("new-key-12345678", merged.get("privateKey"));   // 已覆盖
        assertEquals("old-pub", merged.get("alipayPublicKey"));
    }

    @Test
    void getDecrypted_DB无行走env兜底() {
        when(configMapper.selectByChannel("ALIPAY")).thenReturn(null);

        Map<String, String> m = service.getDecrypted("ALIPAY");
        assertEquals("env-app-id", m.get("appId"));
        assertEquals("env-private-key", m.get("privateKey"));
        assertEquals("env-public-key", m.get("alipayPublicKey"));
    }

    @Test
    void listMasked_未配置渠道configured为false_已配置出tails() {
        PaymentChannelConfigEntity e = new PaymentChannelConfigEntity();
        e.setChannel("ALIPAY");
        e.setConfigTails("{\"appId\":\"****6789\"}");
        when(configMapper.selectByChannel("ALIPAY")).thenReturn(e);
        when(configMapper.selectByChannel("WECHAT")).thenReturn(null);

        List<PaymentChannelConfigVO> list = service.listMasked();
        assertEquals(2, list.size());
        PaymentChannelConfigVO alipay = list.stream().filter(v -> v.getChannel().equals("ALIPAY")).findFirst().orElseThrow();
        PaymentChannelConfigVO wechat = list.stream().filter(v -> v.getChannel().equals("WECHAT")).findFirst().orElseThrow();
        assertTrue(alipay.isConfigured());
        assertEquals("****6789", alipay.getTails().get("appId"));
        assertFalse(wechat.isConfigured());
        assertTrue(wechat.getTails().isEmpty());
    }
}
