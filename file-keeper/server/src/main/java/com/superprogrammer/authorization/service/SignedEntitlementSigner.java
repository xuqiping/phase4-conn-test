package com.superprogrammer.authorization.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * 统一签名授权凭据签发器。
 *
 * <p>使用 Ed25519 非对称签名：服务端持有私钥签发，客户端内嵌公钥验签。
 * 凭据 payload 包含用户、设备、签发时间、绝对过期时间与授权模块列表，
 * 签名覆盖整个 payload，防止篡改与无限续期。</p>
 *
 * <p>输出格式：{@code base64url(payload) + "." + base64url(signature)}。</p>
 */
public final class SignedEntitlementSigner {

    private static final String ALGORITHM = "Ed25519";
    private static final String SEPARATOR = "|";
    private static final String TOKEN_SEPARATOR = ".";
    private static final int PEM_LINE_LENGTH = 64;

    private SignedEntitlementSigner() {
    }

    /**
     * 使用私钥签发授权凭据。
     *
     * @param privateKey 服务端 Ed25519 私钥（PKCS#8）
     * @param userId     用户 ID
     * @param deviceId   设备 ID
     * @param issuedAt   签发时间
     * @param notAfter   绝对过期时间（必须校验）
     * @param allowedModules 授权模块代码列表
     * @return 签名后的授权凭据 token
     */
    public static String sign(PrivateKey privateKey, Long userId, String deviceId,
                              Instant issuedAt, Instant notAfter, List<String> allowedModules) {
        if (notAfter.isBefore(issuedAt)) {
            throw new IllegalArgumentException("notAfter must not be before issuedAt");
        }
        String payload = buildPayload(userId, deviceId, issuedAt, notAfter, allowedModules);
        byte[] signature = sign(privateKey, payload);
        return base64Url(payload) + TOKEN_SEPARATOR + base64Url(signature);
    }

    /**
     * 使用公钥验证授权凭据。
     *
     * @param publicKey 客户端内嵌的 Ed25519 公钥
     * @param token     授权凭据 token
     * @return 解析后的 payload
     */
    public static SignedEntitlementPayload verify(PublicKey publicKey, String token) {
        int dotIndex = token.indexOf(TOKEN_SEPARATOR);
        if (dotIndex < 0) {
            throw new IllegalArgumentException("Invalid signed entitlement token format");
        }
        String payload = unbase64Url(token.substring(0, dotIndex));
        byte[] signature = unbase64UrlBytes(token.substring(dotIndex + 1));
        if (!verify(publicKey, payload, signature)) {
            throw new IllegalArgumentException("Signed entitlement signature mismatch");
        }
        return parsePayload(payload);
    }

    /**
     * 生成一对新的 Ed25519 密钥（仅用于工具/测试）。
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 algorithm not available", e);
        }
    }

    /**
     * 将 PKCS#8 私钥编码为 PEM 格式。
     */
    public static String encodePrivateKeyPem(PrivateKey privateKey) {
        String base64 = Base64.getEncoder().encodeToString(privateKey.getEncoded());
        return wrapPem("PRIVATE KEY", base64);
    }

    /**
     * 将 X.509 公钥编码为 PEM 格式。
     */
    public static String encodePublicKeyPem(PublicKey publicKey) {
        String base64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        return wrapPem("PUBLIC KEY", base64);
    }

    /**
     * 从 PEM 字符串中解码 PKCS#8 Ed25519 私钥。
     */
    public static PrivateKey decodePrivateKeyPem(String pem) {
        try {
            byte[] pkcs8Bytes = decodePem("PRIVATE KEY", pem);
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException("Invalid Ed25519 private key PEM", e);
        }
    }

    /**
     * 从 PEM 字符串中解码 X.509 Ed25519 公钥。
     */
    public static PublicKey decodePublicKeyPem(String pem) {
        try {
            byte[] x509Bytes = decodePem("PUBLIC KEY", pem);
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            return keyFactory.generatePublic(new X509EncodedKeySpec(x509Bytes));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException("Invalid Ed25519 public key PEM", e);
        }
    }

    private static String buildPayload(Long userId, String deviceId, Instant issuedAt,
                                       Instant notAfter, List<String> allowedModules) {
        return userId + SEPARATOR
                + deviceId + SEPARATOR
                + issuedAt.toEpochMilli() + SEPARATOR
                + notAfter.toEpochMilli() + SEPARATOR
                + String.join(",", allowedModules);
    }

    private static SignedEntitlementPayload parsePayload(String payload) {
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 5) {
            throw new IllegalArgumentException("Invalid signed entitlement payload");
        }
        long issuedAtEpochMilli = Long.parseLong(parts[2]);
        long notAfterEpochMilli = Long.parseLong(parts[3]);
        List<String> modules = parts[4].isEmpty() ? List.of() : List.of(parts[4].split(","));
        return new SignedEntitlementPayload(
                Long.parseLong(parts[0]), parts[1], issuedAtEpochMilli, notAfterEpochMilli, modules);
    }

    private static byte[] sign(PrivateKey privateKey, String payload) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw new IllegalStateException("Failed to sign entitlement", e);
        }
    }

    private static boolean verify(PublicKey publicKey, String payload, byte[] signatureBytes) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return signature.verify(signatureBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw new IllegalStateException("Failed to verify entitlement", e);
        }
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String unbase64Url(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static byte[] unbase64UrlBytes(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static String wrapPem(String label, String base64) {
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < base64.length(); i += PEM_LINE_LENGTH) {
            sb.append(base64, i, Math.min(i + PEM_LINE_LENGTH, base64.length())).append('\n');
        }
        sb.append("-----END ").append(label).append("-----\n");
        return sb.toString();
    }

    private static byte[] decodePem(String label, String pem) {
        String header = "-----BEGIN " + label + "-----";
        String footer = "-----END " + label + "-----";
        int headerIndex = pem.indexOf(header);
        int footerIndex = pem.indexOf(footer);
        if (headerIndex < 0 || footerIndex < 0 || footerIndex <= headerIndex) {
            throw new IllegalArgumentException("Invalid PEM format: missing " + label + " boundaries");
        }
        String base64 = pem.substring(headerIndex + header.length(), footerIndex)
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(base64);
    }

    public record SignedEntitlementPayload(Long userId, String deviceId,
                                           long issuedAtEpochMilli, long notAfterEpochMilli,
                                           List<String> allowedModules) {
    }
}
