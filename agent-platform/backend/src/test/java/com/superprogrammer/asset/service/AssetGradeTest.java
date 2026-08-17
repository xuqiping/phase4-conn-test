package com.superprogrammer.asset.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AssetGrade 边界单测（2x#7）：档位边界 94/95/89/90/79/80/69/两端/未评 + 等级区间映射。
 * 前端 constants/assetGrade.ts 对齐单测与此表互为镜像——改档位两处同改。
 */
class AssetGradeTest {

    @Test
    void fromScore_gradeBoundaries() {
        assertNull(AssetGrade.fromScore(null), "未评 → null");
        assertEquals("A+", AssetGrade.fromScore(100));
        assertEquals("A+", AssetGrade.fromScore(95), "95=A+ 下界");
        assertEquals("A", AssetGrade.fromScore(94), "94=A 上界");
        assertEquals("A", AssetGrade.fromScore(90));
        assertEquals("B", AssetGrade.fromScore(89), "89=B 上界");
        assertEquals("B", AssetGrade.fromScore(80));
        assertEquals("C", AssetGrade.fromScore(79), "79=C 上界");
        assertEquals("C", AssetGrade.fromScore(70));
        assertEquals("D", AssetGrade.fromScore(69), "69=D 上界");
        assertEquals("D", AssetGrade.fromScore(0));
    }

    @Test
    void rangeOf_intervalsForFilter() {
        // 等级快捷筛选换算表（AssetProjectView 筛选条 A+=[95,100] 等）
        assertArrayEquals(new int[]{95, 100}, AssetGrade.rangeOf("A+"));
        assertArrayEquals(new int[]{90, 94}, AssetGrade.rangeOf("A"));
        assertArrayEquals(new int[]{80, 89}, AssetGrade.rangeOf("B"));
        assertArrayEquals(new int[]{70, 79}, AssetGrade.rangeOf("C"));
        assertArrayEquals(new int[]{0, 69}, AssetGrade.rangeOf("D"));
    }

    @Test
    void rangeOf_intervalCoversItsGrade() {
        // 区间自洽：区间内任一分映射回同等级（含两端）
        for (String g : AssetGrade.ALL) {
            int[] r = AssetGrade.rangeOf(g);
            assertEquals(g, AssetGrade.fromScore(r[0]), g + " 区间下界");
            assertEquals(g, AssetGrade.fromScore(r[1]), g + " 区间上界");
        }
    }

    @Test
    void rangeOf_unknownGrade_throws() {
        assertThrows(IllegalArgumentException.class, () -> AssetGrade.rangeOf("S"));
        assertThrows(IllegalArgumentException.class, () -> AssetGrade.rangeOf(null));
    }

    @Test
    void rangeOf_returnsDefensiveCopy() {
        int[] first = AssetGrade.rangeOf("A");
        first[0] = 0;
        assertArrayEquals(new int[]{90, 94}, AssetGrade.rangeOf("A"), "外部改副本不得污染映射表");
    }
}
