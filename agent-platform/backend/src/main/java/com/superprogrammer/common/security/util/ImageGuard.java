package com.superprogrammer.common.security.util;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * 图片像素上限护栏（安全体系 S4 · SEC-FR-032，F-3① 像素炸弹）。
 *
 * <p>威胁：宽×高天文数字的恶意图片（如 100000×100000 声明头），{@code ImageIO.read} 按声明尺寸
 * 分配缓冲直接 OOM——解码前必须先读<strong>头</strong>核尺寸。
 *
 * <p>实现：{@code ImageIO.getImageReaders} 取 reader 后只调 {@code getWidth(0)/getHeight(0)}
 * ——只解析文件头元数据，<strong>不解码像素</strong>（正常图零开销，炸弹图在门外来计量）。
 * 读不出宽高（无 reader/损坏）返 null，由调用方按「无法判定」自行决定放行或拒绝——
 * 与 magic number 嗅探同哲学：检测不了不等于恶意。
 */
public final class ImageGuard {

    private static final Logger log = LoggerFactory.getLogger(ImageGuard.class);

    /** 默认总像素上限（1 亿像素 ≈ 10000×10000，覆盖 8K 全景；超此值的业务图极罕见）。 */
    public static final long DEFAULT_MAX_PIXELS = 100_000_000L;

    private ImageGuard() {
    }

    /**
     * 只读图片头取宽高（不解码像素）。无法判定返 null（不抛——容错归调用方）。
     */
    public static int[] dimensions(InputStream in) {
        if (in == null) {
            return null;
        }
        try (ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0) {
                    return null;
                }
                return new int[]{w, h};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            log.warn("image header read failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 像素预算断言：头读取宽高，超上限抛 BAD_REQUEST（调用方在<strong>落盘前</strong>调用，
     * 拒收即无残留）。无法判定（null）放行——现有 magic number/扩展名防线继续兜。
     *
     * @param maxPixels 总像素上限（&le;0 视为默认 {@link #DEFAULT_MAX_PIXELS}）
     * @param label     日志/话术用的文件标识（原始名）
     */
    public static void assertPixels(long maxPixels, InputStream in, String label) {
        long cap = maxPixels > 0 ? maxPixels : DEFAULT_MAX_PIXELS;
        int[] dims = dimensions(in);
        if (dims == null) {
            return;
        }
        long pixels = (long) dims[0] * dims[1];
        if (pixels > cap) {
            log.warn("图片像素超限拒收 label={} {}x{} pixels={} cap={}", label, dims[0], dims[1], pixels, cap);
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "图片分辨率过大（" + dims[0] + "×" + dims[1] + "），超过平台上限，请压缩后上传");
        }
    }
}
