// agent-platform/backend/src/main/java/com/superprogrammer/auth/totp/TotpService.java
package com.superprogrammer.auth.totp;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 安全体系 S5 · SEC-FR-006（A6 管理员 TOTP）：RFC6238 时间型一次性密码，零第三方依赖自实现。
 *
 * <p>算法：secret（Base32 编码的随机字节）+ 当前时间步（30s 一格）→ HMAC-SHA1 → 动态截断 → 6 位码。
 * 校验容忍 ±1 个时间窗（±30s 时钟偏移）；比对用常量时间（防时序侧信道逐位猜码）。</p>
 *
 * <p>另承担：otpauth:// 绑定 URI 生成（前端引导用户加进验证器 App）、
 * 8 组一次性恢复码生成（去混淆字母表，SHA-256 只存哈希）。</p>
 */
@Service
public class TotpService {

    /** RFC4648 Base32 字母表（验证器 App 通用）。 */
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    /** 时间步长（RFC6238 标准 30 秒）。 */
    private static final long TIME_STEP_MS = 30_000L;
    /** 校验窗口：当前步 ±1（容忍手机/服务器 30s 时钟偏移）。 */
    private static final int ALLOWED_DRIFT = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 生成 160bit（20 字节）随机 secret，Base32 编码返回（Google Authenticator 推荐长度）。 */
    public String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** 绑定 URI：otpauth://totp/<issuer>:<account>?secret=...&issuer=...&algorithm=SHA1&digits=6&period=30 */
    public String buildOtpauthUri(String secret, String account, String issuer) {
        return "otpauth://totp/" + urlEncode(issuer) + ":" + urlEncode(account)
                + "?secret=" + secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=6&period=30";
    }

    /**
     * 校验 6 位 TOTP 码（±1 窗口）。任何格式异常 → false（不抛异常，检测层不自残）。
     */
    public boolean verify(String base32Secret, String code, long nowMs) {
        if (base32Secret == null || base32Secret.isBlank()) {
            return false;
        }
        if (code == null || !code.matches("\\d{6}")) {
            return false;
        }
        byte[] key;
        try {
            key = base32Decode(base32Secret);
        } catch (IllegalArgumentException e) {
            return false;
        }
        long currentStep = nowMs / TIME_STEP_MS;
        for (int drift = -ALLOWED_DRIFT; drift <= ALLOWED_DRIFT; drift++) {
            String expected = generateCode(key, currentStep + drift);
            if (constantTimeEquals(expected, code)) {
                return true;
            }
        }
        return false;
    }

    /** 单步生成（暴露给单测对拍 RFC6238 官方测试向量）。 */
    String generateCode(byte[] key, long step) {
        byte[] counter = new byte[8];
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (step & 0xFF);
            step >>>= 8;
        }
        byte[] hash = hmacSha1(key, counter);
        // 动态截断（RFC4226 §5.3）：低 4 bit 定偏移，取 4 字节再抹最高位 → %10^6
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        return String.format("%06d", binary % 1_000_000);
    }

    /** 8 组一次性恢复码：格式 xxxxx-xxxxx，字母表去 0/O/1/I/l 混淆字符。明文只在签发瞬间返回一次。 */
    public List<String> generateRecoveryCodes() {
        String alphabet = "23456789abcdefghjkmnpqrstuvwxyz";
        List<String> codes = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            StringBuilder sb = new StringBuilder(11);
            for (int j = 0; j < 10; j++) {
                if (j == 5) {
                    sb.append('-');
                }
                sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            }
            codes.add(sb.toString());
        }
        return codes;
    }

    /** 恢复码只存哈希：SHA-256 hex（不可逆，库泄露不等于恢复码泄露）。 */
    public String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ---------- 内部工具 ----------

    private static byte[] hmacSha1(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA1 不可用", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private static String base32Encode(byte[] bytes) {
        StringBuilder sb = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    private static byte[] base32Decode(String s) {
        String clean = s.toUpperCase().replaceAll("[=\\s]", "");
        int buffer = 0, bits = 0, count = 0;
        byte[] out = new byte[clean.length() * 5 / 8];
        for (char c : clean.toCharArray()) {
            int val = BASE32_ALPHABET.indexOf(c);
            if (val < 0) {
                throw new IllegalArgumentException("非法 Base32 字符: " + c);
            }
            buffer = (buffer << 5) | val;
            bits += 5;
            if (bits >= 8 && count < out.length) {
                out[count++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
