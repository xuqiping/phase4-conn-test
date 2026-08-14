// agent-platform/backend/src/test/java/com/superprogrammer/common/security/ai/SensitivePatternCatalogTest.java
package com.superprogrammer.common.security.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全体系 S3 · SEC-FR-052：输出侧敏感模式目录单测（与 LogMasker 同源口径，输出面全遮蔽）。
 */
class SensitivePatternCatalogTest {

    @Test
    void 身份证18位_打码() {
        String out = SensitivePatternCatalog.mask("客户证件号是11010119900307863X请登记");
        assertFalse(out.contains("11010119900307863X"));
        assertTrue(out.contains(SensitivePatternCatalog.MASK));
    }

    @Test
    void 手机号_打码() {
        String out = SensitivePatternCatalog.mask("联系电话13812348000");
        assertFalse(out.contains("13812348000"));
        assertEquals("联系电话***", out);
    }

    @Test
    void 银行卡19位_打码且不误伤短数字() {
        assertTrue(SensitivePatternCatalog.hits("卡号6222020200112233445"));
        assertFalse(SensitivePatternCatalog.hits("订单号12345678（8位）"));
    }

    @Test
    void apiKey_kv形态_打码() {
        String out = SensitivePatternCatalog.mask("配置apiKey=sk1234567890abc");
        assertFalse(out.contains("sk1234567890abc"));
        // 纯单词（值不含数字）不吞
        assertFalse(SensitivePatternCatalog.hits("the token is invalid"));
    }

    @Test
    void Bearer_打码() {
        String out = SensitivePatternCatalog.mask("Header: Bearer eyJhbGciOiJIUzI1NiJ9");
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9"));
    }

    @Test
    void 无命中_同引用返回() {
        String text = "今天聊聊架构设计";
        assertSame(text, SensitivePatternCatalog.mask(text));
        assertNull(SensitivePatternCatalog.mask(null));
        assertFalse(SensitivePatternCatalog.hits(null));
    }
}
