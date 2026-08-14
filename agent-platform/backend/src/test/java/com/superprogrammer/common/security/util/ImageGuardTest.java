package com.superprogrammer.common.security.util;

import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 安全体系 S4 · SEC-FR-032（F-3① 像素炸弹）测试。
 * 头读取不解码：真 PNG 宽高可读；预算超限拒收；无法判定放行。
 */
class ImageGuardTest {

    /** 造真 PNG 字节（6×4 纯色）——ImageIO 编码，头信息合法。 */
    private static byte[] tinyPng() throws Exception {
        BufferedImage img = new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    void dimensions_readsHeaderOnly() throws Exception {
        try (InputStream in = new ByteArrayInputStream(tinyPng())) {
            int[] dims = ImageGuard.dimensions(in);
            assertThat(dims).isNotNull();
            assertThat(dims[0]).isEqualTo(6);
            assertThat(dims[1]).isEqualTo(4);
        }
    }

    @Test
    void dimensions_garbageReturnsNull() throws Exception {
        try (InputStream in = new ByteArrayInputStream(new byte[]{1, 2, 3, 4})) {
            assertThat(ImageGuard.dimensions(in)).isNull();
        }
    }

    @Test
    void dimensions_nullStreamReturnsNull() {
        assertThat(ImageGuard.dimensions(null)).isNull();
    }

    // AC：总像素超预算 → BAD_REQUEST（头读取即拒，不解码像素）
    @Test
    void assertPixels_overBudgetRejected() throws Exception {
        try (InputStream in = new ByteArrayInputStream(tinyPng())) {
            assertThatThrownBy(() -> ImageGuard.assertPixels(10, in, "bomb.png"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("分辨率过大");
        }
    }

    // AC：预算内放行
    @Test
    void assertPixels_withinBudgetPasses() throws Exception {
        try (InputStream in = new ByteArrayInputStream(tinyPng())) {
            assertThatCode(() -> ImageGuard.assertPixels(100, in, "ok.png"))
                    .doesNotThrowAnyException();
        }
    }

    // AC：无法判定（垃圾字节）放行——检测不了不等于恶意
    @Test
    void assertPixels_undetectablePasses() throws Exception {
        try (InputStream in = new ByteArrayInputStream(new byte[]{9, 9, 9})) {
            assertThatCode(() -> ImageGuard.assertPixels(10, in, "odd.png"))
                    .doesNotThrowAnyException();
        }
    }

    // AC：maxPixels ≤ 0 → 默认上限（6×4 远小于 1 亿，放行）
    @Test
    void assertPixels_nonPositiveCapUsesDefault() throws Exception {
        try (InputStream in = new ByteArrayInputStream(tinyPng())) {
            assertThatCode(() -> ImageGuard.assertPixels(0, in, "ok.png"))
                    .doesNotThrowAnyException();
        }
    }
}
