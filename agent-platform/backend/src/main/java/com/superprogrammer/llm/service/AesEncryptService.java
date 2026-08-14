package com.superprogrammer.llm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Service
public class AesEncryptService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    /** 内置默认密钥（随代码进仓库=公开）：任何能看到代码的人都可解密库里所有密文。 */
    static final String DEFAULT_SECRET = "default-secret-key-change-in-production!!";
    /** 生产态最低密钥长度（此服务对 secret 先 SHA-256 派生，长度只影响熵，16 字符=96 bit 熵下限）。 */
    private static final int MIN_SECRET_LENGTH = 16;

    @Value("${llm.encryption.secret:" + DEFAULT_SECRET + "}")
    private String secret;

    @Value("${app.cors.allowed-origins:}")
    private String corsAllowedOrigins;

    private final SecureRandom random = new SecureRandom();

    public void setSecret(String secret) {
        this.secret = secret;
    }

    /** 单测注入生产态信号（@Value 字段无 setter）。 */
    void setCorsAllowedOriginsForTest(String corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    /**
     * 安全体系 S5 · SEC-FR-074（G5）：生产态弱密钥 fail-fast（复用 JwtUtil KNOWN_WEAK 范式）。
     *
     * <p>「生产态」判定 = {@code app.cors.allowed-origins} 非空（该配置只有生产/预发才设置，
     * 与 CORS 白名单同信号源）；此时密钥为内置默认值或长度 &lt; 16 → 拒绝启动——
     * 默认密钥随 git 仓库公开，apiKey/密钥列（api_key_enc）等于明文裸奔。
     * <p>dev（CORS 空）→ 放行 + WARN，不打断本地起服务（库里的密文也全是测试数据）。
     */
    @jakarta.annotation.PostConstruct
    void validateSecret() {
        boolean productionMode = corsAllowedOrigins != null && !corsAllowedOrigins.isBlank();
        String trimmed = secret == null ? "" : secret.trim();
        boolean weak = DEFAULT_SECRET.equals(trimmed) || trimmed.length() < MIN_SECRET_LENGTH;
        if (!weak) {
            return;
        }
        if (productionMode) {
            throw new IllegalStateException(
                    "llm.encryption.secret 为内置默认值或长度<" + MIN_SECRET_LENGTH
                            + "，且 app.cors.allowed-origins 已配置（生产态）——加密密钥形同虚设。"
                            + "请经环境变量 LLM_ENCRYPTION_SECRET 注入 ≥16 字符随机密钥后重启"
                            + "（生成: openssl rand -base64 32 | tr -d '\\n'；注意：换密钥需重录存量 apiKey 密文）。");
        }
        log.warn("llm.encryption.secret 使用弱密钥（默认值/过短）——仅限 dev 放行；"
                + "生产环境必须经 LLM_ENCRYPTION_SECRET 注入随机密钥（S5 SEC-FR-074 fail-fast 会在生产态拒启动）");
    }

    /**
     * 用 SHA-256 派生固定 32 字节 AES 密钥，避免原始 secret 长度不合规
     */
    private SecretKeySpec deriveKey() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("密钥派生失败", e);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            SecretKeySpec keySpec = deriveKey();
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("加密失败", e);
        }
    }

    public String decrypt(String ciphertext) {
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);
            SecretKeySpec keySpec = deriveKey();
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("解密失败", e);
        }
    }
}
