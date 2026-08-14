package com.superprogrammer.llm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AesEncryptServiceTest {

    private AesEncryptService service;

    @BeforeEach
    void setUp() {
        service = new AesEncryptService();
        service.setSecret("test-secret-key-32-bytes-long!!!");
    }

    @Test
    void encryptAndDecrypt_shouldReturnOriginalText() {
        String original = "sk-my-api-key-12345";
        String encrypted = service.encrypt(original);
        assertNotEquals(original, encrypted);
        String decrypted = service.decrypt(encrypted);
        assertEquals(original, decrypted);
    }

    @Test
    void encrypt_shouldReturnDifferentCiphertextEachTime() {
        String original = "same-text";
        String enc1 = service.encrypt(original);
        String enc2 = service.encrypt(original);
        assertNotEquals(enc1, enc2);
    }

    @Test
    void decrypt_withInvalidInput_shouldThrow() {
        assertThrows(Exception.class, () -> service.decrypt("not-valid-base64"));
    }

    // ---- 安全体系 S5 · SEC-FR-074（G5）：生产态弱密钥 fail-fast ----

    @Test
    void validateSecret_productionDefaultSecret_rejected() {
        service.setSecret(AesEncryptService.DEFAULT_SECRET);
        service.setCorsAllowedOriginsForTest("https://app.example.com");
        assertThrows(IllegalStateException.class, service::validateSecret);
    }

    @Test
    void validateSecret_productionShortSecret_rejected() {
        service.setSecret("short");
        service.setCorsAllowedOriginsForTest("https://app.example.com");
        assertThrows(IllegalStateException.class, service::validateSecret);
    }

    @Test
    void validateSecret_productionStrongSecret_passes() {
        service.setSecret("a-very-long-random-production-secret!!");
        service.setCorsAllowedOriginsForTest("https://app.example.com");
        assertDoesNotThrow(service::validateSecret);
    }

    @Test
    void validateSecret_devDefaultSecret_warnOnly() {
        // dev（CORS 未配置）→ 放行 WARN，不打断本地起服务
        service.setSecret(AesEncryptService.DEFAULT_SECRET);
        service.setCorsAllowedOriginsForTest("");
        assertDoesNotThrow(service::validateSecret);
    }

    // ---- Phase4 修正：prod profile 主信号（Nginx 同源生产不配 CORS，仅靠 CORS 信号会漏检） ----

    @Test
    void validateSecret_prodProfileNoCorsDefaultSecret_rejected() {
        service.setSecret(AesEncryptService.DEFAULT_SECRET);
        service.setActiveProfileForTest("prod");
        service.setCorsAllowedOriginsForTest("");
        assertThrows(IllegalStateException.class, service::validateSecret);
    }

    @Test
    void validateSecret_devProfileDefaultSecret_warnOnly() {
        service.setSecret(AesEncryptService.DEFAULT_SECRET);
        service.setActiveProfileForTest("dev");
        service.setCorsAllowedOriginsForTest("");
        assertDoesNotThrow(service::validateSecret);
    }
}
