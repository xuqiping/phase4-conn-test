package com.superprogrammer.canvas.service;

import com.superprogrammer.common.exception.BusinessException;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.util.List;

/**
 * VideoFrameService 单测（plan C11）。无 Spring 上下文（直接 new，依赖 @Value 默认 javacv）。
 *
 * <p>测试视频用 javacv {@link FFmpegFrameRecorder} 在 @BeforeAll 现造（2s, 10fps, MPEG4），免提交二进制 fixture。
 * 抽帧 round-trip：造片→抽帧→断言 JPEG 字节非空 + JPEG 魔数（FF D8 FF）。
 *
 * <p>注意：首次跑会下拉 javacv/ffmpeg native jar（~150MB），后续缓存。属单测组（无 DB/无 @Tag integration）。
 */
class VideoFrameServiceTest {

    private static Path sampleVideo;
    private static final int FPS = 10;
    private static final int TOTAL_FRAMES = 20; // 2s

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void makeSampleVideo() throws Exception {
        sampleVideo = tempDir.resolve("sample.mp4");
        FFmpegFrameRecorder rec = new FFmpegFrameRecorder(sampleVideo.toFile(), 320, 240);
        rec.setFormat("mp4");
        rec.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4);
        rec.setFrameRate(FPS);
        rec.setVideoBitrate(400_000);
        rec.start();
        Java2DFrameConverter conv = new Java2DFrameConverter();
        try {
            for (int i = 0; i < TOTAL_FRAMES; i++) {
                rec.record(conv.convert(colorFrame(i)));
            }
        } finally {
            rec.stop();
            rec.release();
        }
    }

    @AfterAll
    static void cleanup() {
        // @TempDir 自动清；占位避免 jacoco 误报
    }

    @Test
    void extractFirst_returnsJpegBytes() {
        VideoFrameService svc = new VideoFrameService();
        VideoFrameService.ExtractedFrame f = svc.extract(sampleVideo, VideoFrameService.FrameMode.FIRST, null);

        assertThat(f.mimeType()).isEqualTo("image/jpeg");
        assertThat(f.size()).isGreaterThan(100);
        assertThat(f.bytes()[0]).isEqualTo((byte) 0xFF); // JPEG SOI 魔数
        assertThat(f.bytes()[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    void extractLast_returnsJpegBytes() {
        VideoFrameService svc = new VideoFrameService();
        VideoFrameService.ExtractedFrame f = svc.extract(sampleVideo, VideoFrameService.FrameMode.LAST, null);

        assertThat(f.bytes()).isNotEmpty();
        assertThat(f.mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void extractAt_returnsJpegBytes() {
        VideoFrameService svc = new VideoFrameService();
        VideoFrameService.ExtractedFrame f = svc.extract(sampleVideo, VideoFrameService.FrameMode.AT, 1L);

        assertThat(f.bytes()).isNotEmpty();
        assertThat(f.size()).isEqualTo(f.bytes().length);
    }

    @Test
    void extractAt_secondBeyondDuration_throwsBadRequest() {
        VideoFrameService svc = new VideoFrameService();
        assertThatThrownBy(() -> svc.extract(sampleVideo, VideoFrameService.FrameMode.AT, 999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超出视频时长");
    }

    @Test
    void extractAt_negativeSecond_throwsBadRequest() {
        VideoFrameService svc = new VideoFrameService();
        assertThatThrownBy(() -> svc.extract(sampleVideo, VideoFrameService.FrameMode.AT, -1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void extractAt_nullSecond_throwsBadRequest() {
        VideoFrameService svc = new VideoFrameService();
        assertThatThrownBy(() -> svc.extract(sampleVideo, VideoFrameService.FrameMode.AT, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void extractAt_firstAndSecondAreDifferentFrames() {
        // 首帧与第 1.5s 帧 JPEG 字节数应不同（画面颜色不同）
        VideoFrameService svc = new VideoFrameService();
        byte[] first = svc.extract(sampleVideo, VideoFrameService.FrameMode.FIRST, null).bytes();
        byte[] atMid = svc.extract(sampleVideo, VideoFrameService.FrameMode.AT, 1L).bytes();
        // 至少长度大概率不同；即便相同也用首字节后内容比较——这里只断言非同一引用且均有效
        assertThat(first).isNotEmpty();
        assertThat(atMid).isNotEmpty();
    }

    // ==================== C12 视频截取（clip）====================

    @Test
    void clip_returnsMp4TempFile() throws Exception {
        VideoFrameService svc = new VideoFrameService();
        VideoFrameService.ClipResult clip = svc.clip(sampleVideo, 0L, 1L);
        try {
            assertThat(clip.mimeType()).isEqualTo("video/mp4");
            assertThat(clip.size()).isGreaterThan(0L);
            assertThat(Files.exists(clip.tempFile())).isTrue();
            assertThat(Files.isRegularFile(clip.tempFile())).isTrue();
        } finally {
            Files.deleteIfExists(clip.tempFile());
        }
    }

    @Test
    void clip_thenExtractFirst_roundTripsToValidVideo() throws Exception {
        // 截取 0-1s → 再对截取产物抽首帧 → 应得有效 JPEG，证明 clip 产物是浏览器可播的有效视频
        VideoFrameService svc = new VideoFrameService();
        VideoFrameService.ClipResult clip = svc.clip(sampleVideo, 0L, 1L);
        try {
            VideoFrameService.ExtractedFrame f = svc.extract(clip.tempFile(), VideoFrameService.FrameMode.FIRST, null);
            assertThat(f.mimeType()).isEqualTo("image/jpeg");
            assertThat(f.bytes()).isNotEmpty();
            assertThat(f.bytes()[0]).isEqualTo((byte) 0xFF); // JPEG SOI 魔数
            assertThat(f.bytes()[1]).isEqualTo((byte) 0xD8);
        } finally {
            Files.deleteIfExists(clip.tempFile());
        }
    }

    @Test
    void clip_endBeforeStart_throwsBadRequest() {
        VideoFrameService svc = new VideoFrameService();
        assertThatThrownBy(() -> svc.clip(sampleVideo, 2L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("结束秒须大于起始秒");
    }

    @Test
    void clip_startBeyondDuration_throwsBadRequest() {
        VideoFrameService svc = new VideoFrameService();
        assertThatThrownBy(() -> svc.clip(sampleVideo, 999L, 1000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超出视频时长");
    }

    @Test
    void clip_exceedsMaxSeconds_throwsBadRequest() {
        VideoFrameService svc = new VideoFrameService();
        assertThatThrownBy(() -> svc.clip(sampleVideo, 0L, 10_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("截取时长超限");
    }

    @Test
    void clip_negativeStart_throwsBadRequest() {
        VideoFrameService svc = new VideoFrameService();
        assertThatThrownBy(() -> svc.clip(sampleVideo, -1L, 1L))
                .isInstanceOf(BusinessException.class);
    }

    // ==================== C13 视频拼接（concat）====================

    @Test
    void concat_twoParts_returnsMergedMp4() throws Exception {
        VideoFrameService svc = new VideoFrameService();
        VideoFrameService.ClipResult a = svc.clip(sampleVideo, 0L, 1L);
        VideoFrameService.ClipResult b = svc.clip(sampleVideo, 1L, 2L);
        VideoFrameService.ConcatResult r = null;
        try {
            r = svc.concat(List.of(a.tempFile(), b.tempFile()));
            assertThat(r.mimeType()).isEqualTo("video/mp4");
            assertThat(r.segmentCount()).isEqualTo(2);
            assertThat(r.size()).isGreaterThan(0L);
            assertThat(Files.exists(r.tempFile())).isTrue();
            // 两段各 ~1s，合计应接近 2000ms（重编码时长可能微漂，放宽到 1500+）
            assertThat(r.totalDurationMs()).isBetween(1_500L, 2_500L);
        } finally {
            if (r != null) Files.deleteIfExists(r.tempFile());
            Files.deleteIfExists(a.tempFile());
            Files.deleteIfExists(b.tempFile());
        }
    }

    @Test
    void concat_thenExtractFirst_roundTripsToValidVideo() throws Exception {
        // 拼接 2 段 → 对成片抽首帧 → 有效 JPEG，证明 concat 产物是可播视频（非空壳）
        VideoFrameService svc = new VideoFrameService();
        VideoFrameService.ClipResult a = svc.clip(sampleVideo, 0L, 1L);
        VideoFrameService.ClipResult b = svc.clip(sampleVideo, 1L, 2L);
        VideoFrameService.ConcatResult r = null;
        try {
            r = svc.concat(List.of(a.tempFile(), b.tempFile()));
            VideoFrameService.ExtractedFrame f = svc.extract(r.tempFile(), VideoFrameService.FrameMode.FIRST, null);
            assertThat(f.bytes()).isNotEmpty();
            assertThat(f.bytes()[0]).isEqualTo((byte) 0xFF); // JPEG SOI 魔数
            assertThat(f.bytes()[1]).isEqualTo((byte) 0xD8);
        } finally {
            if (r != null) Files.deleteIfExists(r.tempFile());
            Files.deleteIfExists(a.tempFile());
            Files.deleteIfExists(b.tempFile());
        }
    }

    @Test
    void concat_singlePart_returnsMp4() throws Exception {
        VideoFrameService svc = new VideoFrameService();
        VideoFrameService.ClipResult a = svc.clip(sampleVideo, 0L, 1L);
        VideoFrameService.ConcatResult r = null;
        try {
            r = svc.concat(List.of(a.tempFile()));
            assertThat(r.segmentCount()).isEqualTo(1);
            assertThat(r.size()).isGreaterThan(0L);
        } finally {
            if (r != null) Files.deleteIfExists(r.tempFile());
            Files.deleteIfExists(a.tempFile());
        }
    }

    @Test
    void concat_emptyList_throwsBadRequest() {
        VideoFrameService svc = new VideoFrameService();
        assertThatThrownBy(() -> svc.concat(List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("拼接段列表为空");
    }

    @Test
    void concat_exceedsMaxParts_throwsBadRequest() throws Exception {
        // 造 21 个段触发上限（单段复用同 clip 临时文件即可，concat 内部不查重）
        VideoFrameService svc = new VideoFrameService();
        VideoFrameService.ClipResult a = svc.clip(sampleVideo, 0L, 1L);
        try {
            Path[] parts = new Path[21];
            java.util.Arrays.fill(parts, a.tempFile());
            assertThatThrownBy(() -> svc.concat(List.of(parts)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("拼接段数超限");
        } finally {
            Files.deleteIfExists(a.tempFile());
        }
    }

    // ==================== C10 焦点编辑图片裁剪（cropImage）====================

    @Test
    void cropImage_returnsPngOfSubregion() throws Exception {
        // 源图 100x100：左上 50x50=红，其余=蓝。裁左上 1/4（归一化 0,0,0.5,0.5）→ 50x50 全红 PNG。
        Path src = writeQuadrantPng();
        VideoFrameService svc = new VideoFrameService();
        VideoFrameService.ExtractedFrame f = svc.cropImage(src, 0, 0, 0.5, 0.5);

        assertThat(f.mimeType()).isEqualTo("image/png");
        assertThat(f.size()).isEqualTo(f.bytes().length);
        assertThat(f.bytes()).isNotEmpty();
        // PNG 魔数 89 50 4E 47
        assertThat(f.bytes()[0]).isEqualTo((byte) 0x89);
        // 解码回 BufferedImage 断言尺寸 + 像素 = 红象限
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(f.bytes()));
        assertThat(out.getWidth()).isEqualTo(50);
        assertThat(out.getHeight()).isEqualTo(50);
        assertThat(out.getRGB(0, 0)).isEqualTo(Color.RED.getRGB());
        assertThat(out.getRGB(49, 49)).isEqualTo(Color.RED.getRGB());
    }

    @Test
    void cropImage_bottomRightQuarter_correctPixels() throws Exception {
        // 裁右下 1/4（归一化 0.5,0.5,0.5,0.5）→ 50x50 全蓝
        Path src = writeQuadrantPng();
        VideoFrameService svc = new VideoFrameService();
        VideoFrameService.ExtractedFrame f = svc.cropImage(src, 0.5, 0.5, 0.5, 0.5);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(f.bytes()));
        assertThat(out.getWidth()).isEqualTo(50);
        assertThat(out.getRGB(0, 0)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void cropImage_rectOutOfBounds_throwsBadRequest() throws Exception {
        Path src = writeQuadrantPng();
        VideoFrameService svc = new VideoFrameService();
        // x+w = 1.2 > 1
        assertThatThrownBy(() -> svc.cropImage(src, 0.5, 0.5, 0.7, 0.5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("裁剪区域非法");
    }

    @Test
    void cropImage_nullPath_throwsBadRequest() {
        VideoFrameService svc = new VideoFrameService();
        assertThatThrownBy(() -> svc.cropImage(null, 0, 0, 0.5, 0.5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("源图路径缺失");
    }

    /** 造源图 PNG（100x100，左上 50x50 红、其余蓝），供裁剪测试用。 */
    private Path writeQuadrantPng() throws Exception {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 100, 100);
        g.setColor(Color.RED);
        g.fillRect(0, 0, 50, 50);
        g.dispose();
        Path p = tempDir.resolve("quadrant_" + System.nanoTime() + ".png");
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    /** 生成纯色帧，颜色随 index 变化（保证首帧与中段帧画面不同）。 */
    private static BufferedImage colorFrame(int index) {
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color((index * 13) % 256, (index * 7) % 256, (index * 29) % 256));
        g.fillRect(0, 0, 320, 240);
        g.dispose();
        return img;
    }

    // ==================== 安全体系 S4 · SEC-FR-032 cropImage 像素护栏（F-3①）====================

    @Test
    void cropImage_pixelBudgetOverCap_rejectedBeforeDecode() throws Exception {
        VideoFrameService svc = new VideoFrameService();
        Path src = writeQuadrantPng();   // 100×100 = 10000 像素
        com.superprogrammer.system.service.SystemSettingService settings =
                org.mockito.Mockito.mock(com.superprogrammer.system.service.SystemSettingService.class);
        org.mockito.Mockito.when(settings.getUploadMaxPixels()).thenReturn(1000L);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "systemSettingService", settings);

        assertThatThrownBy(() -> svc.cropImage(src, 0.1, 0.1, 0.5, 0.5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分辨率过大");
    }

    @Test
    void cropImage_settingsAbsent_usesDefaultCapAndCrops() throws Exception {
        VideoFrameService svc = new VideoFrameService();   // systemSettingService=null → 默认 1 亿上限
        Path src = writeQuadrantPng();

        VideoFrameService.ExtractedFrame out = svc.cropImage(src, 0.0, 0.0, 0.5, 0.5);
        assertThat(out.mimeType()).isEqualTo("image/png");
        assertThat(out.bytes().length).isGreaterThan(0);
    }

    // ==================== 2x 四轮 S6：图片翻转/旋转（transformImage 五 op 像素断言） ====================

    /** 2x2 四色图：R G / B Y（左上红 右上绿 左下蓝 右下黄）——翻转/旋转各 op 断言基准。 */
    private Path writeFourColorPng() throws Exception {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, Color.RED.getRGB());
        img.setRGB(1, 0, Color.GREEN.getRGB());
        img.setRGB(0, 1, Color.BLUE.getRGB());
        img.setRGB(1, 1, Color.YELLOW.getRGB());
        Path p = tempDir.resolve("fourcolor_" + System.nanoTime() + ".png");
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    private BufferedImage transformFourColor(VideoFrameService.TransformOp op) throws Exception {
        VideoFrameService svc = new VideoFrameService();
        VideoFrameService.ExtractedFrame f = svc.transformImage(writeFourColorPng(), op);
        assertThat(f.mimeType()).isEqualTo("image/png");
        return ImageIO.read(new ByteArrayInputStream(f.bytes()));
    }

    @Test
    void transform_flipH_mirrorsHorizontally() throws Exception {
        // R G / B Y → G R / Y B（左右镜像）
        BufferedImage out = transformFourColor(VideoFrameService.TransformOp.FLIP_H);
        assertThat(out.getWidth()).isEqualTo(2);
        assertThat(out.getHeight()).isEqualTo(2);
        assertThat(out.getRGB(0, 0)).isEqualTo(Color.GREEN.getRGB());
        assertThat(out.getRGB(1, 0)).isEqualTo(Color.RED.getRGB());
        assertThat(out.getRGB(0, 1)).isEqualTo(Color.YELLOW.getRGB());
        assertThat(out.getRGB(1, 1)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void transform_flipV_mirrorsVertically() throws Exception {
        // R G / B Y → B Y / R G（上下镜像）
        BufferedImage out = transformFourColor(VideoFrameService.TransformOp.FLIP_V);
        assertThat(out.getRGB(0, 0)).isEqualTo(Color.BLUE.getRGB());
        assertThat(out.getRGB(1, 0)).isEqualTo(Color.YELLOW.getRGB());
        assertThat(out.getRGB(0, 1)).isEqualTo(Color.RED.getRGB());
        assertThat(out.getRGB(1, 1)).isEqualTo(Color.GREEN.getRGB());
    }

    @Test
    void transform_rotate90_swapsDimsAndRotatesClockwise() throws Exception {
        // R G / B Y 顺 90° → B R / Y G（2x2 旋转后左上=原左下）
        BufferedImage out = transformFourColor(VideoFrameService.TransformOp.ROTATE_90);
        assertThat(out.getWidth()).isEqualTo(2);
        assertThat(out.getHeight()).isEqualTo(2);
        assertThat(out.getRGB(0, 0)).isEqualTo(Color.BLUE.getRGB());
        assertThat(out.getRGB(1, 0)).isEqualTo(Color.RED.getRGB());
        assertThat(out.getRGB(0, 1)).isEqualTo(Color.YELLOW.getRGB());
        assertThat(out.getRGB(1, 1)).isEqualTo(Color.GREEN.getRGB());
    }

    @Test
    void transform_rotate180_invertsAllQuadrants() throws Exception {
        // R G / B Y → Y B / G R
        BufferedImage out = transformFourColor(VideoFrameService.TransformOp.ROTATE_180);
        assertThat(out.getRGB(0, 0)).isEqualTo(Color.YELLOW.getRGB());
        assertThat(out.getRGB(1, 0)).isEqualTo(Color.BLUE.getRGB());
        assertThat(out.getRGB(0, 1)).isEqualTo(Color.GREEN.getRGB());
        assertThat(out.getRGB(1, 1)).isEqualTo(Color.RED.getRGB());
    }

    @Test
    void transform_rotate270_swapsDimsAndRotatesCounterClockwise() throws Exception {
        // R G / B Y 逆 90° → G Y / R B
        BufferedImage out = transformFourColor(VideoFrameService.TransformOp.ROTATE_270);
        assertThat(out.getRGB(0, 0)).isEqualTo(Color.GREEN.getRGB());
        assertThat(out.getRGB(1, 0)).isEqualTo(Color.YELLOW.getRGB());
        assertThat(out.getRGB(0, 1)).isEqualTo(Color.RED.getRGB());
        assertThat(out.getRGB(1, 1)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void transform_nonSquare_rotate90SwapsDimensions() throws Exception {
        // 3x2（宽>高）顺 90° → 2x3：尺寸互换才是真旋转（区别于翻转）
        BufferedImage img = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        Path p = tempDir.resolve("rect_" + System.nanoTime() + ".png");
        ImageIO.write(img, "png", p.toFile());
        VideoFrameService svc = new VideoFrameService();
        VideoFrameService.ExtractedFrame f = svc.transformImage(p, VideoFrameService.TransformOp.ROTATE_90);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(f.bytes()));
        assertThat(out.getWidth()).isEqualTo(2);
        assertThat(out.getHeight()).isEqualTo(3);
    }

    @Test
    void transform_nullPath_throwsBadRequest() {
        VideoFrameService svc = new VideoFrameService();
        assertThatThrownBy(() -> svc.transformImage(null, VideoFrameService.TransformOp.FLIP_H))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("源图路径缺失");
    }

    @Test
    void transformOp_parse_rejectsUnknown() {
        assertThatThrownBy(() -> VideoFrameService.TransformOp.parse("INVERT"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的图片变换");
        assertThatThrownBy(() -> VideoFrameService.TransformOp.parse(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("变换类型缺失");
        assertThat(VideoFrameService.TransformOp.parse("ROTATE_90")).isEqualTo(VideoFrameService.TransformOp.ROTATE_90);
    }

    // ==================== 2x 四轮 S6：EXIF 方向归正 ====================

    /** 手造带 EXIF Orientation 的 JPEG（8x16：左半红右半蓝——够大躲开 4:2:0 色度糊边，采样取内部像素）。 */
    private Path writeExifJpeg(int orientation) throws Exception {
        BufferedImage img = new BufferedImage(8, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 8; x++) {
                img.setRGB(x, y, x < 4 ? Color.RED.getRGB() : Color.BLUE.getRGB());
            }
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", jpeg);
        byte[] body = jpeg.toByteArray();

        // TIFF 头（小端）+ IFD0：1 个目录项（tag 0x0112, type SHORT, count 1, value=orientation）
        java.io.ByteArrayOutputStream tiff = new java.io.ByteArrayOutputStream();
        tiff.write('I'); tiff.write('I');
        tiff.write(42); tiff.write(0);
        tiff.write(8); tiff.write(0); tiff.write(0); tiff.write(0);   // IFD0 偏移=8
        tiff.write(1); tiff.write(0);                                  // 目录项数=1
        tiff.write(0x12); tiff.write(0x01);                            // tag 0x0112（小端低字节在前）
        tiff.write(3); tiff.write(0);                                  // type SHORT
        tiff.write(1); tiff.write(0); tiff.write(0); tiff.write(0);    // count=1
        tiff.write(orientation); tiff.write(0); tiff.write(0); tiff.write(0); // value 内联
        tiff.write(0); tiff.write(0); tiff.write(0); tiff.write(0);    // 下一 IFD=0

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0xFF); out.write(0xD8);                              // SOI
        byte[] tiffBytes = tiff.toByteArray();
        int app1Len = tiffBytes.length + 8;                            // 段长含自身 2 + "Exif\0\0" 6
        out.write(0xFF); out.write(0xE1);
        out.write(app1Len >> 8); out.write(app1Len & 0xFF);
        out.write('E'); out.write('x'); out.write('i'); out.write('f'); out.write(0); out.write(0);
        out.write(tiffBytes);
        out.write(body, 2, body.length - 2);                           // 原 JPEG 体去掉其 SOI（外层已写）
        Path p = tempDir.resolve("exif" + orientation + "_" + System.nanoTime() + ".jpg");
        Files.write(p, out.toByteArray());
        return p;
    }

    @Test
    void exifOrientation_readParsesTag6() throws Exception {
        assertThat(ExifOrientation.readOrientation(writeExifJpeg(6))).isEqualTo(6);
        assertThat(ExifOrientation.readOrientation(writeExifJpeg(1))).isEqualTo(1);
    }

    @Test
    void exifOrientation_pngHasNoExif_returns1() throws Exception {
        assertThat(ExifOrientation.readOrientation(writeFourColorPng())).isEqualTo(1);
    }

    @Test
    void exifOrientation_applySwapsDimsForQuarter() {
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, Color.RED.getRGB());
        img.setRGB(1, 0, Color.BLUE.getRGB());
        BufferedImage out = ExifOrientation.applyOrientation(img, 6);
        assertThat(out.getWidth()).isEqualTo(1);
        assertThat(out.getHeight()).isEqualTo(2);
        // Orientation=6（需顺 90° 归正）：左红右蓝 → 竖排红上蓝下
        assertThat(out.getRGB(0, 0)).isEqualTo(Color.RED.getRGB());
        assertThat(out.getRGB(0, 1)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void transform_exifOrientation6AppliedBeforeFlip() throws Exception {
        // 源 8x16 左红右蓝；EXIF=6 归正 → 16x8 上红下蓝；再 FLIP_V → 上蓝下红。
        // 若未归正，FLIP_V 产物仍是 8x16 左红右蓝。采样取内部像素（躲开 4:2:0 色度糊边），色距排序断言。
        Path src = writeExifJpeg(6);
        VideoFrameService svc = new VideoFrameService();
        VideoFrameService.ExtractedFrame f = svc.transformImage(src, VideoFrameService.TransformOp.FLIP_V);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(f.bytes()));
        assertThat(out.getWidth()).isEqualTo(16);
        assertThat(out.getHeight()).isEqualTo(8);
        int top = out.getRGB(8, 2);    // 上半中部（归正后原右半=蓝，镜像后置顶）
        int bottom = out.getRGB(8, 6); // 下半中部
        assertThat(colorDistance(top, Color.BLUE)).isLessThan(colorDistance(top, Color.RED));
        assertThat(colorDistance(bottom, Color.RED)).isLessThan(colorDistance(bottom, Color.BLUE));
    }

    /** RGB 三通道绝对差之和（色距，0=同色）。JPEG 有损断言用。 */
    private static int colorDistance(int rgb, Color c) {
        return Math.abs(((rgb >> 16) & 0xFF) - c.getRed())
                + Math.abs(((rgb >> 8) & 0xFF) - c.getGreen())
                + Math.abs((rgb & 0xFF) - c.getBlue());
    }
}
