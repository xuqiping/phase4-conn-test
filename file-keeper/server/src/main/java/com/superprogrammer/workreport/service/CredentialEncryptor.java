package com.superprogrammer.workreport.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Component
public class CredentialEncryptor {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int KEY_LENGTH = 32;

    @Value("${work-report.credential-key:}")
    private String key;

    private SecretKey secretKey;
    private boolean encryptionEnabled;

    @PostConstruct
    public void init() {
        if (key == null || key.isBlank()) {
            log.warn("未配置 work-report.credential-key，推送凭据将以明文存储（仅开发环境允许）");
            this.encryptionEnabled = false;
            return;
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] normalizedKey = new byte[KEY_LENGTH];
        System.arraycopy(keyBytes, 0, normalizedKey, 0, Math.min(keyBytes.length, KEY_LENGTH));
        this.secretKey = new SecretKeySpec(normalizedKey, ALGORITHM);
        this.encryptionEnabled = true;
    }

    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        if (!encryptionEnabled) {
            return plainText;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            throw new RuntimeException("凭据加密失败", e);
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        if (!encryptionEnabled) {
            return cipherText;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(cipherText);
            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] cipherBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherBytes);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plainBytes = cipher.doFinal(cipherBytes);

            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("凭据解密失败", e);
        }
    }
}
