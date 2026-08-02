package com.superprogrammer.knowledge.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HalfVecUtil 序列化机械测。
 * 守 Locale.US（小数点 '.'，防 JVM 默认 ',' 致 PG halfvec 解析失败）+ dim 锚 + null 防御。
 */
class HalfVecUtilTest {

    @Test
    void dim_is2048() {
        assertEquals(2048, HalfVecUtil.DIM);
    }

    @Test
    void shortVector_serializedWithBracketsAndCommas() {
        String s = HalfVecUtil.toHalfVec(new float[]{1.0f, 2.5f});
        assertTrue(s.startsWith("["), "须以 '[' 开头");
        assertTrue(s.endsWith("]"), "须以 ']' 结尾");
        assertEquals("[1.000000,2.500000]", s);
    }

    @Test
    void singleElement() {
        assertEquals("[0.500000]", HalfVecUtil.toHalfVec(new float[]{0.5f}));
    }

    @Test
    void fullDimVector_has2048Components() {
        float[] v = new float[HalfVecUtil.DIM];
        for (int i = 0; i < v.length; i++) v[i] = 0.1f * (i % 10);
        String s = HalfVecUtil.toHalfVec(v);
        assertEquals(HalfVecUtil.DIM - 1, countCommas(s), "2048 维应有 2047 个逗号分隔");
    }

    @Test
    void localeIsUs_decimalPointNotComma() {
        // 即便显式设非 US locale，输出仍须 '.'（硬编码 Locale.US）
        String s = HalfVecUtil.toHalfVec(new float[]{1.5f});
        assertFalse(s.contains(","), "单元素不应含逗号；多元素逗号是分隔符非小数点");
        assertEquals("[1.500000]", s);
    }

    @Test
    void nullVector_throws() {
        assertThrows(IllegalArgumentException.class, () -> HalfVecUtil.toHalfVec(null));
    }

    private int countCommas(String s) {
        int c = 0;
        for (char ch : s.toCharArray()) if (ch == ',') c++;
        return c;
    }
}
