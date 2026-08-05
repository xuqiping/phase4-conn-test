package com.superprogrammer.canvas.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

/**
 * 视频抽帧（plan C11 / IC-12 / R-2）。javacv {@link FFmpegFrameGrabber} 流式 seek→grab 单帧→JPEG 字节。
 *
 * <p><b>纯 Java 自包含</b>：javacv + ffmpeg-platform native，无系统 ffmpeg 依赖（AGENTS 禁 sidecar subprocess，
 * 故抽帧走后端 Java 不进 sidecar）。仅 {@code VideoFrameService} 一处用 javacv，不扰其它包。
 *
 * <p><b>三种模式</b>：
 * <ul>
 *   <li>{@link FrameMode#FIRST} — 开 Grabber 后直接 grabImage（不 seek）。</li>
 *   <li>{@link FrameMode#LAST} — {@code setTimestamp(duration - 200ms)} 再 grab（预留 200ms 防尾帧空洞）。</li>
 *   <li>{@link FrameMode#AT} — {@code setTimestamp(second * 1_000_000μs)} 再 grab；秒数越界 BAD_REQUEST。</li>
 * </ul>
 *
 * <p><b>资源</b>：Grabber 流式按帧取，不整片 load 入内存（plan R-2「大视频内存可控」）；finally 必 release。
 *
 * <p><b>错误处理</b>：失败固定话术不透传 {@code e.getMessage()}（plan 安全清单）；秒数越界抛 BAD_REQUEST。
 *
 * <p><b>可观测性</b>：抽帧打日志（mode/second/duration/costMs/bytes），复用 media traceId 风格。
 *
 * <p>配置开关 {@code canvas.frame-extractor}（plan 运维清单「配置开关」）：默认 {@code javacv}；
 * {@code ffmpeg}（系统进程）分支留后续，当前未实现→UNPROCESSABLE 引导。
 */
@Slf4j
@Service
public class VideoFrameService {

    /** 尾帧预留（微秒）。seek 到 duration-200ms，避免某些容器尾帧空洞抓空。 */
    private static final long TAIL_EPSILON_US = 200_000L;

    /** AT 模式秒数上限（plan 安全清单「输入校验」：防极端值撑爆 seek）。 */
    private static final long MAX_SECOND_AT = 86_400L;

    @Value("${canvas.frame-extractor:javacv}")
    private String extractorBackend = "javacv";

    public enum FrameMode { FIRST, LAST, AT }

    /** 抽帧产物（JPEG 字节 + mime + size）。调用方落 stored_files(SOURCE_CANVAS)。 */
    public record ExtractedFrame(byte[] bytes, String mimeType, long size) {}

    /**
     * 从视频抽单帧。
     *
     * @param videoPath 本地视频路径（已过 FileStorageService.load 归属咽喉点）
     * @param mode      FIRST/LAST/AT
     * @param secondAt  仅 AT 用，单位秒；越界 [0,duration] 抛 BAD_REQUEST
     * @return JPEG 字节
     */
    public ExtractedFrame extract(Path videoPath, FrameMode mode, Long secondAt) {
        if (!"javacv".equalsIgnoreCase(extractorBackend)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "当前抽帧后端仅支持 javacv（ffmpeg 系统进程分支未实现）");
        }
        if (videoPath == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "视频路径缺失");
        }
        if (mode == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "抽帧模式缺失");
        }
        if (mode == FrameMode.AT) {
            if (secondAt == null || secondAt < 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "抽帧秒数非法");
            }
            if (secondAt > MAX_SECOND_AT) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "抽帧秒数超限");
            }
        }

        long started = System.currentTimeMillis();
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toFile());
        try {
            grabber.start();
            long durationUs = grabber.getLengthInTime(); // 微秒；某些流式/VFR 视频可能为 0
            if (mode == FrameMode.LAST) {
                if (durationUs <= 0) {
                    throw new BusinessException(ErrorCode.UNPROCESSABLE, "无法读取视频时长，尾帧抽取失败");
                }
                long ts = Math.max(0, durationUs - TAIL_EPSILON_US);
                grabber.setTimestamp(ts);
            } else if (mode == FrameMode.AT) {
                if (durationUs > 0 && secondAt * 1_000_000L > durationUs) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "抽帧秒数超出视频时长");
                }
                grabber.setTimestamp(secondAt * 1_000_000L);
            }
            // FIRST：不 seek，直接抓首个图像帧

            Frame frame = grabber.grabImage();
            if (frame == null || frame.image == null) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE, "抽帧失败：未抓取到画面");
            }
            Java2DFrameConverter converter = new Java2DFrameConverter();
            BufferedImage img = converter.convert(frame);
            if (img == null) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE, "抽帧失败：画面转换失败");
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", baos);
            byte[] bytes = baos.toByteArray();
            long costMs = System.currentTimeMillis() - started;
            log.info("canvas frame extracted: mode={} secondAt={} durationMs={} costMs={} bytes={}",
                    mode, secondAt, durationUs / 1_000, costMs, bytes.length);
            return new ExtractedFrame(bytes, "image/jpeg", bytes.length);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("canvas frame extract failed: mode={} secondAt={} err={}", mode, secondAt, e.getMessage());
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "抽帧失败，请稍后重试");
        } finally {
            try {
                grabber.release();
            } catch (Exception ignored) {
                // release 失败不阻断主流程
            }
        }
    }
}
