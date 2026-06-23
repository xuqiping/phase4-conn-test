package com.superprogrammer.knowledge.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HashUtil SHA-256 机械测。
 * 用于 content_hash（I1/I3）+ idempotency_key（I4）+ permission_signature（P3），确定性是地基。
 */
class HashUtilTest {

    @Test
    void knownVector_hello() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                HashUtil.sha256("hello"));
    }

    @Test
    void emptyString_knownHash() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                HashUtil.sha256(""));
    }

    @Test
    void nullString_sameAsEmpty() {
        // null → 空字节数组 → 与 "" 同 hash
        assertEquals(HashUtil.sha256(""), HashUtil.sha256((String) null));
    }

    @Test
    void deterministic_sameInputSameOutput() {
        String a = HashUtil.sha256("deterministic input");
        String b = HashUtil.sha256("deterministic input");
        assertEquals(a, b);
    }

    @Test
    void differentInput_differentHash() {
        assertNotEquals(HashUtil.sha256("foo"), HashUtil.sha256("bar"));
    }

    @Test
    void outputIs64HexChars() {
        String h = HashUtil.sha256("any");
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]{64}"), "须为 64 位小写十六进制");
    }

    @Test
    void byteOverload_matchesStringOverload() {
        String text = "abc";
        assertEquals(HashUtil.sha256(text.getBytes(StandardCharsets.UTF_8)),
                HashUtil.sha256(text));
    }

    @Test
    void nullBytes_sameAsEmpty() {
        assertEquals(HashUtil.sha256(new byte[0]), HashUtil.sha256((byte[]) null));
    }
}
