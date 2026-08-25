package com.superprogrammer.media.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.media.config.ImageModelCapability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3（6x/Q5）：比例推导单测——7 比例 × 3 档位（2K/3K/4K）全矩阵落像素区间且宽高比正确；
 * 1K/1.5K 档拒绝；非白名单比例/未知档位拒绝；能力覆盖（自定义 ratios/上下限）生效。
 */
class ImageSizeDeriverTest {

    private static final List<String> RATIOS = List.of("1:1", "4:3", "3:4", "16:9", "9:16", "3:2", "2:3");
    private static final List<String> TIERS = List.of("2K", "3K", "4K");
    private static final long MIN = 3_686_400L;
    private static final long MAX = 16_777_216L;

    /** 解析 "WxH"。 */
    private static long[] wh(String s) {
        String[] p = s.split("x");
        return new long[]{Long.parseLong(p[0]), Long.parseLong(p[1])};
    }

    @Test
    void 全矩阵_7比例x3档位_像素落区间且比例正确() {
        for (String ratio : RATIOS) {
            String[] ab = ratio.split(":");
            double expect = Double.parseDouble(ab[0]) / Double.parseDouble(ab[1]);
            for (String tier : TIERS) {
                long[] r = wh(ImageSizeDeriver.derive(ratio, tier, null));
                long pixels = r[0] * r[1];
                assertTrue(pixels >= MIN && pixels <= MAX,
                        ratio + "+" + tier + " 像素 " + pixels + " 越界 [" + MIN + "," + MAX + "]");
                double actual = (double) r[0] / r[1];
                assertTrue(Math.abs(actual - expect) / expect < 0.02,
                        ratio + "+" + tier + " 实际比例 " + actual + " 偏离 " + expect);
            }
        }
    }

    @Test
    void 档位缺省_默认2K() {
        assertEquals(ImageSizeDeriver.derive("1:1", "2K", null),
                ImageSizeDeriver.derive("1:1", null, null));
        assertEquals(ImageSizeDeriver.derive("1:1", "2K", null),
                ImageSizeDeriver.derive("1:1", "  ", null));
    }

    @Test
    void 档位预算_等面积锚点() {
        // 1:1 时 W=H=档位边长（面积=档位预算）：2K→2048x2048，4K→4096x4096
        assertEquals("2048x2048", ImageSizeDeriver.derive("1:1", "2K", null));
        assertEquals("4096x4096", ImageSizeDeriver.derive("1:1", "4K", null));
    }

    @Test
    void 四K十六比九_舍入贴上限但不越界() {
        // sqrt(4096²×16/9)≈5461.3 → W=5461、H=3072，总像素 16,776,192 恰在上限 4096² 之下（留 1024 余量）
        assertEquals("5461x3072", ImageSizeDeriver.derive("16:9", "4K", null));
    }

    @Test
    void 低档位_1K与1d5K_拒绝并给指引() {
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> ImageSizeDeriver.derive("16:9", "1K", null));
        assertTrue(e1.getMessage().contains("不支持比例模式"), e1.getMessage());
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> ImageSizeDeriver.derive("1:1", "1.5K", null));
        assertTrue(e2.getMessage().contains("自定义宽x高"), e2.getMessage());
    }

    @Test
    void 非白名单比例_拒绝() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> ImageSizeDeriver.derive("21:9", "2K", null));
        assertTrue(e.getMessage().contains("比例非法"), e.getMessage());
    }

    @Test
    void 未知档位_拒绝() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> ImageSizeDeriver.derive("16:9", "8K", null));
        assertTrue(e.getMessage().contains("档位非法"), e.getMessage());
    }

    @Test
    void 能力覆盖_自定义白名单与像素上下限生效() {
        ImageModelCapability cap = ImageModelCapability.builder()
                .ratios(List.of("21:9"))
                .minTotalPixels(1_000_000L)
                .maxTotalPixels(100_000_000L)
                .build();
        // 21:9 默认白名单外 → 覆盖后放行；下限放宽后 1K 档也可
        String s = ImageSizeDeriver.derive("21:9", "2K", cap);
        assertTrue(s.matches("\\d+x\\d+"), s);
        assertTrue(ImageSizeDeriver.derive("21:9", "1K", cap).matches("\\d+x\\d+"));
        // 覆盖白名单后默认 16:9 反被拒
        assertThrows(BusinessException.class, () -> ImageSizeDeriver.derive("16:9", "2K", cap));
    }
}
