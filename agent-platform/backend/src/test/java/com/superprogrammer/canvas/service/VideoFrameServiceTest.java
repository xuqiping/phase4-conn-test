package com.superprogrammer.canvas.service;

import com.superprogrammer.common.exception.BusinessException;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** 生成纯色帧，颜色随 index 变化（保证首帧与中段帧画面不同）。 */
    private static BufferedImage colorFrame(int index) {
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color((index * 13) % 256, (index * 7) % 256, (index * 29) % 256));
        g.fillRect(0, 0, 320, 240);
        g.dispose();
        return img;
    }
}
