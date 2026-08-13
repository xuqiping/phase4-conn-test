// agent-platform/backend/src/test/java/com/superprogrammer/common/security/InjectionDetectorTest.java
package com.superprogrammer.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InjectionDetector 单测（11x P2-C5）：SQLi/XSS/PATH 命中 + URL 解码 + 误报防线（正常文本不命中）。
 */
class InjectionDetectorTest {

    @Test
    void normalText_noHit() {
        assertNull(InjectionDetector.detect("hello world"));
        assertNull(InjectionDetector.detect("用户名 O'Brien")); // 单引号单词不构成组合模式
        assertNull(InjectionDetector.detect("价格 1=1 对比"));   // 无引号前缀的 1=1 不算恒真式
        assertNull(InjectionDetector.detect(""));
        assertNull(InjectionDetector.detect(null));
    }

    @Test
    void sqli_unionSelect_hits() {
        var hit = InjectionDetector.detect("1 UNION SELECT username,password FROM users");
        assertNotNull(hit);
        assertEquals(SecurityEventTypes.SQLI_PROBE, hit.eventType());
    }

    @Test
    void sqli_orTautology_hits() {
        assertEquals(SecurityEventTypes.SQLI_PROBE,
                InjectionDetector.detect("admin' OR 1=1 --").eventType());
        assertEquals(SecurityEventTypes.SQLI_PROBE,
                InjectionDetector.detect("x' or 'a'='a").eventType());
    }

    @Test
    void sqli_dropTable_hits() {
        assertEquals(SecurityEventTypes.SQLI_PROBE,
                InjectionDetector.detect("1; DROP TABLE users").eventType());
    }

    @Test
    void sqli_urlEncoded_hitsAfterDecode() {
        // %27%20OR%201%3D1-- → ' OR 1=1--
        var hit = InjectionDetector.detect("%27%20OR%201%3D1--");
        assertNotNull(hit);
        assertEquals(SecurityEventTypes.SQLI_PROBE, hit.eventType());
    }

    @Test
    void sqli_doubleEncoded_hitsAfterTwiceDecode() {
        // %2527%2520OR%25201%253D1 → %27%20OR%201%3D1 → ' OR 1=1
        var hit = InjectionDetector.detect("%2527%2520OR%25201%253D1");
        assertNotNull(hit);
        assertEquals(SecurityEventTypes.SQLI_PROBE, hit.eventType());
    }

    @Test
    void xss_scriptTag_hits() {
        assertEquals(SecurityEventTypes.XSS_PROBE,
                InjectionDetector.detect("<script>alert(1)</script>").eventType());
        assertEquals(SecurityEventTypes.XSS_PROBE,
                InjectionDetector.detect("<img src=x onerror=alert(1)>").eventType());
        assertEquals(SecurityEventTypes.XSS_PROBE,
                InjectionDetector.detect("javascript:alert(1)").eventType());
    }

    @Test
    void path_traversal_hits() {
        assertEquals(SecurityEventTypes.PATH_PROBE,
                InjectionDetector.detect("../../etc/passwd").eventType());
        assertEquals(SecurityEventTypes.PATH_PROBE,
                InjectionDetector.detect("..\\..\\windows\\win.ini%00.jpg").eventType());
    }

    @Test
    void snippet_sanitizedAndCapped() {
        String long80 = "x".repeat(200) + "' OR 1=1 --";
        var hit = InjectionDetector.detect(long80);
        assertNotNull(hit);
        assertTrue(hit.snippet().length() <= 80);
    }
}
