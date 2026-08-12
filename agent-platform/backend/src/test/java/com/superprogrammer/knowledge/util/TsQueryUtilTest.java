package com.superprogrammer.knowledge.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * V77 · TsQueryUtil 单测：to_tsquery OR 串构造 + 特殊字符丢弃 + 边界。
 * <p>
 * 验：多 token → {@code a | b | c}（OR 活 BM25）；单 token 原样；空/纯空白 → ""；
 * 特殊字符 {@code !:&|()'\} 丢弃（防注入/语法崩）。
 */
class TsQueryUtilTest {

    @Test
    void multiToken_joinedWithOr() {
        assertEquals("杭州 | 旅游 | 攻略", TsQueryUtil.toOrQuery("杭州 旅游 攻略"));
    }

    @Test
    void singleToken_returnedAsIs() {
        assertEquals("萧山", TsQueryUtil.toOrQuery("萧山"));
    }

    @Test
    void nullOrBlank_returnsEmpty() {
        assertEquals("", TsQueryUtil.toOrQuery(null));
        assertEquals("", TsQueryUtil.toOrQuery(""));
        assertEquals("", TsQueryUtil.toOrQuery("   "));
    }

    @Test
    void extraWhitespace_collapsed() {
        assertEquals("a | b | c", TsQueryUtil.toOrQuery("  a   b\tc "));
    }

    @Test
    void specialChars_dropped() {
        // jieba 词基本不含特殊字符，这里防注入/语法崩：!:&|()'\ 全丢，正常 CJK 保留
        assertEquals("杭州旅游攻略", TsQueryUtil.toOrQuery("杭!州:旅&游|攻()略'\\"));
    }

    @Test
    void allSpecialCharsOnly_returnsEmpty() {
        assertEquals("", TsQueryUtil.toOrQuery("!:&|()\\'"));
    }
}
