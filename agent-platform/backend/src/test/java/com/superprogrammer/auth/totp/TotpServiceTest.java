// agent-platform/backend/src/test/java/com/superprogrammer/auth/totp/TotpServiceTest.java
package com.superprogrammer.auth.totp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全体系 S5 · SEC-FR-006（A6 TOTP）单测：RFC6238 官方测试向量对拍 + 窗口容忍 + 恢复码。
 */
class TotpServiceTest {

    private final TotpService totpService = new TotpService();

    /** RFC6238 Appendix B 官方向量：ASCII secret "12345678901234567890"，SHA1/6位。 */
    private static final String RFC_SECRET_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void rfc6238Vectors_match() {
        // T=59s → 287082；T=1111111109 → 081804；T=1234567890 → 005924（官方附录 B SHA1 6 位列）
        assertEquals("287082", totpService.generateCode(rfcKey(), 59_000L / 30_000L));
        assertEquals("081804", totpService.generateCode(rfcKey(), 1_111_111_109_000L / 30_000L));
        assertEquals("005924", totpService.generateCode(rfcKey(), 1_234_567_890_000L / 30_000L));
    }

    @Test
    void verify_currentStep_accepted() {
        long now = 59_000L;
        String code = totpService.generateCode(rfcKey(), now / 30_000L);
        assertTrue(totpService.verify(RFC_SECRET_BASE32, code, now));
    }

    @Test
    void verify_plusMinusOneWindow_accepted() {
        long now = 1_000_000L;
        long step = now / 30_000L;
        // 手机制造商默认 ±30s 时钟偏移：上一格/下一格的码也要过
        assertTrue(totpService.verify(RFC_SECRET_BASE32, totpService.generateCode(rfcKey(), step - 1), now));
        assertTrue(totpService.verify(RFC_SECRET_BASE32, totpService.generateCode(rfcKey(), step + 1), now));
        // 超出 ±1 窗口拒
        assertFalse(totpService.verify(RFC_SECRET_BASE32, totpService.generateCode(rfcKey(), step + 2), now));
    }

    @Test
    void verify_wrongCode_rejected() {
        long now = 59_000L;
        String code = totpService.generateCode(rfcKey(), now / 30_000L);
        // 篡改末位（等值跳过重算）
        String tampered = code.endsWith("2") ? code.substring(0, 5) + "7" : code.substring(0, 5) + "2";
        assertFalse(totpService.verify(RFC_SECRET_BASE32, tampered, now));
    }

    @Test
    void verify_malformedInput_rejectedNotThrown() {
        assertFalse(totpService.verify(null, "123456", System.currentTimeMillis()));
        assertFalse(totpService.verify("", "123456", System.currentTimeMillis()));
        assertFalse(totpService.verify(RFC_SECRET_BASE32, null, System.currentTimeMillis()));
        assertFalse(totpService.verify(RFC_SECRET_BASE32, "12345", System.currentTimeMillis()));   // 5 位
        assertFalse(totpService.verify(RFC_SECRET_BASE32, "12a456", System.currentTimeMillis())); // 非数字
        assertFalse(totpService.verify("!!!not-base32!!!", "123456", System.currentTimeMillis()));
    }

    @Test
    void generateSecret_roundTripVerifiable() {
        String secret = totpService.generateSecret();
        // Base32 字母表 + 长度 32（20 字节 → 32 字符，无 padding）
        assertTrue(secret.matches("[A-Z2-7]+"));
        assertEquals(32, secret.length());
        long now = System.currentTimeMillis();
        String code = totpService.generateCode(decodeBase32(secret), now / 30_000L);
        assertTrue(totpService.verify(secret, code, now));
    }

    @Test
    void otpauthUri_standardFormat() {
        String uri = totpService.buildOtpauthUri(RFC_SECRET_BASE32, "admin@x", "AgentPlatform");
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("secret=" + RFC_SECRET_BASE32));
        assertTrue(uri.contains("digits=6"));
        assertTrue(uri.contains("period=30"));
    }

    @Test
    void recoveryCodes_eightUniqueWellFormed() {
        List<String> codes = totpService.generateRecoveryCodes();
        assertEquals(8, codes.size());
        assertEquals(8, codes.stream().distinct().count());   // 无重复
        for (String c : codes) {
            assertTrue(c.matches("[2-9a-hj-km-np-z]{5}-[2-9a-hj-km-np-z]{5}"), "混淆字符字母表: " + c);
        }
    }

    @Test
    void sha256Hex_knownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                totpService.sha256Hex("abc"));
    }

    // ---------- 工具 ----------

    private byte[] rfcKey() {
        return decodeBase32(RFC_SECRET_BASE32);
    }

    /** 测试侧独立 Base32 解码（不经被测类，保证对拍独立性）。 */
    private byte[] decodeBase32(String s) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int buffer = 0, bits = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (char c : s.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
