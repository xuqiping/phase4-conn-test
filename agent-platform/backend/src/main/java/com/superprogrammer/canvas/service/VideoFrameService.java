package com.superprogrammer.canvas.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    /** 单次截取时长上限（秒，plan 安全清单「输入校验」：防空切片撑爆磁盘/内存）。 */
    private static final long MAX_CLIP_SECONDS = 600L;

    /** 单次拼接段数上限（plan C13 安全清单：防空切片/防滥用）。 */
    private static final int MAX_CONCAT_PARTS = 20;

    // ============================ 安全体系 S4 · SEC-FR-032（F-3③ 抽帧超时/帧预算） ============================

    /** FFmpeg IO 层超时（微秒，30s）：损坏/恶意容器让 native 读卡死时有限时返回（rw_timeout）。 */
    private static final long FFMPEG_RW_TIMEOUT_US = 30_000_000L;

    /** clip 帧预算：600s × 120fps 上限（防时间戳异常导致逐帧死循环占线程）。 */
    private static final int CLIP_MAX_FRAMES = 72_000;

    /** concat 累计帧预算：20 段 × 600s × 30fps 量级（同样防 native 死循环）。 */
    private static final int CONCAT_MAX_FRAMES = 360_000;

    /** 统一 grabber 预备：IO 超时 option（必须在 start() 前设置）。 */
    private static void prepare(FFmpegFrameGrabber grabber) {
        grabber.setOption("rw_timeout", String.valueOf(FFMPEG_RW_TIMEOUT_US));
    }

    @Value("${canvas.frame-extractor:javacv}")
    private String extractorBackend = "javacv";

    /** 安全体系 S4 · SEC-FR-032 像素上限读取（F-3①，cropImage 解码前护栏）。横切可选依赖范式。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.superprogrammer.system.service.SystemSettingService systemSettingService;

    public enum FrameMode { FIRST, LAST, AT }

    /** 抽帧产物（JPEG 字节 + mime + size）。调用方落 stored_files(SOURCE_CANVAS)。 */
    public record ExtractedFrame(byte[] bytes, String mimeType, long size) {}

    /**
     * 截取产物（临时 mp4 文件 + mime + size）。
     *
     * <p>返回临时文件路径（非字节），避免大片段撑爆内存。调用方落 stored_files(SOURCE_CANVAS) 后
     * <strong>必须</strong> {@link Files#deleteIfExists(Path)} 删临时文件（controller try-finally）。
     */
    public record ClipResult(Path tempFile, String mimeType, long size) {}

    /** 拼接产物（临时 mp4 + mime + size + 总时长 ms + 段数）。调用方落库后须删临时文件。 */
    public record ConcatResult(Path tempFile, String mimeType, long size, long totalDurationMs, int segmentCount) {}

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
            prepare(grabber);   // S4 F-3③：IO 超时
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

    /**
     * 图片裁剪（焦点编辑框选区 → 新图）。归一化坐标 [0,1] × 原图自然尺寸 getSubimage → PNG 字节。
     *
     * <p>非 AI：纯像素裁剪，确定性产物（区别于「生图提取元素」的概念）。前端 FocusEditOverlay
     * 框选得 px 矩形，按 stage 尺寸归一化为 0-1 传本方法，本方法按源图自然像素换算回整数像素裁剪。
     *
     * <p>输出 PNG（无损，避免二次 JPEG 压缩；裁剪区通常不大，体积可控）。校验归一化区间合法。
     *
     * @param srcPath 源图路径（已过 FileStorageService.loadPath 归属咽喉点）
     * @param nx      裁剪区左上角 x 归一化（0-1）
     * @param ny      裁剪区左上角 y 归一化（0-1）
     * @param nw      裁剪区宽归一化（0-1）
     * @param nh      裁剪区高归一化（0-1）
     * @return PNG 字节（mime=image/png）
     */
    public ExtractedFrame cropImage(Path srcPath, double nx, double ny, double nw, double nh) {
        if (srcPath == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "源图路径缺失");
        }
        // 归一化合法性：均在 [0,1]，且矩形不越界（x+w<=1 / y+h<=1）
        if (nx < 0 || ny < 0 || nw <= 0 || nh <= 0 || nx + nw > 1.0001 || ny + nh > 1.0001) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "裁剪区域非法");
        }

        long started = System.currentTimeMillis();
        // S4 F-3①：解码前头读取核像素预算（ImageIO.read 按声明尺寸分配缓冲，炸弹图直接 OOM）
        try (java.io.InputStream guardIn = Files.newInputStream(srcPath)) {
            long cap = systemSettingService == null
                    ? com.superprogrammer.common.security.util.ImageGuard.DEFAULT_MAX_PIXELS
                    : systemSettingService.getUploadMaxPixels();
            com.superprogrammer.common.security.util.ImageGuard.assertPixels(cap, guardIn, srcPath.toString());
        } catch (BusinessException be) {
            throw be;
        } catch (IOException e) {
            log.warn("canvas cropImage pixel guard failed(放行): {}", e.getMessage());
        }
        BufferedImage src;
        try {
            src = ImageIO.read(srcPath.toFile());
        } catch (IOException e) {
            log.warn("canvas cropImage read failed: err={}", e.getMessage());
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "源图读取失败，无法裁剪");
        }
        if (src == null) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "源图格式不支持，无法裁剪");
        }
        int ow = src.getWidth();
        int oh = src.getHeight();
        if (ow <= 0 || oh <= 0) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "源图尺寸异常，无法裁剪");
        }
        // 归一化 → 整数像素（clamp 到源图边界，防浮点误差越界）
        int sx = clampPixel((int) Math.round(nx * ow), 0, ow - 1);
        int sy = clampPixel((int) Math.round(ny * oh), 0, oh - 1);
        int sw = clampPixel((int) Math.round(nw * ow), 1, ow - sx);
        int sh = clampPixel((int) Math.round(nh * oh), 1, oh - sy);

        BufferedImage sub = src.getSubimage(sx, sy, sw, sh);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(sub, "png", baos);
            byte[] bytes = baos.toByteArray();
            long costMs = System.currentTimeMillis() - started;
            log.info("canvas image cropped: srcW={} srcH={} rect=[{},{},{},{}] outBytes={} costMs={}",
                    ow, oh, sx, sy, sw, sh, bytes.length, costMs);
            return new ExtractedFrame(bytes, "image/png", bytes.length);
        } catch (IOException e) {
            log.warn("canvas cropImage encode failed: err={}", e.getMessage());
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "裁剪图编码失败");
        }
    }

    private static int clampPixel(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * 视频截取（plan C12 / IC-13）。时间段 [startSec,endSec) 裁剪 → 新 mp4 临时文件。
     *
     * <p>实现：{@link FFmpegFrameGrabber} seek 到 startSec，逐帧 {@link FFmpegFrameGrabber#grabImage()}
     * 重编码到 {@link FFmpegFrameRecorder}（H.264/mp4，浏览器 {@code <video>} 可直接播），时间戳 ≥ endSec 停。
     * 流式按帧处理不整片 load（同 extract 的 R-2 内存口径）；产物写临时文件而非 byte[]（大片段不撑爆堆）。
     *
     * <p><b>MVP 范围</b>：仅视频轨重编码（不含音频轨）。截取片段静音——浏览器播放/下载正常，
     * 音频轨裁剪需额外 grabSamples + AAC 编码 + A/V 同步，留后续（plan C12 验证「产出可播」已满足）。
     *
     * <p>校验（plan 安全清单「输入校验」）：start≥0 / end&gt;start / 时长 ≤ {@link #MAX_CLIP_SECONDS} /
     * start/end 不超视频时长（duration 不可读时跳过越界校验，靠 MAX_CLIP_SECONDS 兜底）。
     *
     * @param videoPath 本地视频路径（已过 FileStorageService.loadPath 归属咽喉点）
     * @param startSec  起始秒（≥0）
     * @param endSec    结束秒（&gt;startSec）
     * @return 截取产物（临时文件路径 + mime + size）；调用方负责删临时文件
     */
    public ClipResult clip(Path videoPath, long startSec, long endSec) {
        if (!"javacv".equalsIgnoreCase(extractorBackend)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "当前截取后端仅支持 javacv（ffmpeg 系统进程分支未实现）");
        }
        if (videoPath == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "视频路径缺失");
        }
        if (startSec < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "截取起始秒非法");
        }
        if (endSec <= startSec) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "截取结束秒须大于起始秒");
        }
        if (endSec - startSec > MAX_CLIP_SECONDS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "截取时长超限（最长 " + MAX_CLIP_SECONDS + " 秒）");
        }

        long started = System.currentTimeMillis();
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toFile());
        Path tempFile;
        try {
            tempFile = Files.createTempFile("canvas-clip-", ".mp4");
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "截取失败：无法创建临时文件");
        }

        FFmpegFrameRecorder recorder = null;
        try {
            prepare(grabber);   // S4 F-3③：IO 超时
            grabber.start();
            long durationUs = grabber.getLengthInTime();
            long startUs = startSec * 1_000_000L;
            long endUs = endSec * 1_000_000L;
            if (durationUs > 0) {
                if (startUs >= durationUs) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "截取起始秒超出视频时长");
                }
                if (endUs > durationUs) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "截取结束秒超出视频时长");
                }
            }

            // seek 到起点；某些容器 seek 后首帧时间戳需用 grabber.getTimestamp() 校验
            grabber.setTimestamp(startUs);

            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            double fps = grabber.getFrameRate();
            if (fps <= 0) {
                fps = 25.0;
            }

            recorder = new FFmpegFrameRecorder(tempFile.toFile(), width, height);
            recorder.setFormat("mp4");
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setFrameRate(fps);
            recorder.setVideoBitrate(grabber.getVideoBitrate() > 0 ? grabber.getVideoBitrate() : 2_000_000);
            recorder.start();

            Frame frame;
            int recorded = 0;
            while ((frame = grabber.grabImage()) != null) {
                // 时间戳在 seek 后单调推进；到达 endUs 停（容错：seek 精度误差靠 ≤ 判定收尾）
                if (grabber.getTimestamp() >= endUs) {
                    break;
                }
                recorder.record(frame);
                recorded++;
                // S4 F-3③ 帧预算：时间戳异常/容器恶意时不无限逐帧（600s×120fps 上限）
                if (recorded >= CLIP_MAX_FRAMES) {
                    throw new BusinessException(ErrorCode.UNPROCESSABLE, "视频截取时长异常，已中止");
                }
            }

            long size = Files.size(tempFile);
            long costMs = System.currentTimeMillis() - started;
            log.info("canvas clip done: startSec={} endSec={} frames={} durationMs={} costMs={} size={}",
                    startSec, endSec, recorded, durationUs / 1_000, costMs, size);
            return new ClipResult(tempFile, "video/mp4", size);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("canvas clip failed: startSec={} endSec={} err={}", startSec, endSec, e.getMessage());
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "视频截取失败，请稍后重试");
        } finally {
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (Exception ignored) {
                    // 停录失败不阻断（临时文件已写，后续 storeStream 失败由 controller 兜底删）
                }
                try {
                    recorder.release();
                } catch (Exception ignored) {
                    // release 失败不阻断主流程
                }
            }
            try {
                grabber.release();
            } catch (Exception ignored) {
                // release 失败不阻断主流程
            }
        }
    }

    /**
     * 视频拼接（plan C13 / IC-11）。把多段视频按顺序首尾相接 → 单个 mp4 临时文件（基础剪辑成片）。
     *
     * <p>实现：以<strong>首段</strong>的 width/height/fps/bitrate 初始化一个 {@link FFmpegFrameRecorder}，
     * 逐段 {@link FFmpegFrameGrabber#grabImage()} 把帧重编码进同一 recorder。所有段共用首段编码参数，
     * 故 <b>要求各段同源/同尺寸</b>（典型 = 同一视频的若干 clip 截取产物）；尺寸不一致时 javacv 按原始像素塞入，
     * 可能变形——MVP 不做缩放对齐（plan 边界：深度剪辑独立 plan）。
     *
     * <p>同 extract/clip：流式按帧不整片 load；产物写临时文件非 byte[]（调用方 try-finally 删）；
     * 仅视频轨（无音频轨拼接，留后续）；失败固定话术。
     *
     * <p>校验：parts 非空 / 段数 ≤ {@link #MAX_CONCAT_PARTS}；首段尺寸可读。
     *
     * @param parts 按序拼接的源视频路径列表（已过 FileStorageService.loadPath 归属咽喉点）
     * @return 拼接产物（临时文件 + mime + size + 总时长 ms + 段数）
     */
    public ConcatResult concat(List<Path> parts) {
        if (!"javacv".equalsIgnoreCase(extractorBackend)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "当前拼接后端仅支持 javacv（ffmpeg 系统进程分支未实现）");
        }
        if (parts == null || parts.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "拼接段列表为空");
        }
        if (parts.size() > MAX_CONCAT_PARTS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "拼接段数超限（最多 " + MAX_CONCAT_PARTS + " 段）");
        }

        long started = System.currentTimeMillis();
        Path tempFile;
        try {
            tempFile = Files.createTempFile("canvas-concat-", ".mp4");
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "拼接失败：无法创建临时文件");
        }

        FFmpegFrameRecorder recorder = null;
        FFmpegFrameGrabber grabber = null;
        boolean recorderStarted = false;
        long totalDurationUs = 0;
        int totalFrames = 0;
        try {
            for (int i = 0; i < parts.size(); i++) {
                grabber = new FFmpegFrameGrabber(parts.get(i).toFile());
                prepare(grabber);   // S4 F-3③：IO 超时
                grabber.start();
                long durUs = grabber.getLengthInTime();
                if (durUs > 0) {
                    totalDurationUs += durUs;
                }

                if (i == 0) {
                    int width = grabber.getImageWidth();
                    int height = grabber.getImageHeight();
                    if (width <= 0 || height <= 0) {
                        throw new BusinessException(ErrorCode.UNPROCESSABLE, "拼接失败：无法读取首段视频尺寸");
                    }
                    double fps = grabber.getFrameRate();
                    if (fps <= 0) {
                        fps = 25.0;
                    }
                    recorder = new FFmpegFrameRecorder(tempFile.toFile(), width, height);
                    recorder.setFormat("mp4");
                    recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
                    recorder.setFrameRate(fps);
                    recorder.setVideoBitrate(grabber.getVideoBitrate() > 0 ? grabber.getVideoBitrate() : 2_000_000);
                    recorder.start();
                    recorderStarted = true;
                }

                Frame frame;
                while ((frame = grabber.grabImage()) != null) {
                    recorder.record(frame);
                    // S4 F-3③ 累计帧预算：跨段累计，防多段恶意容器叠加死循环
                    if (++totalFrames >= CONCAT_MAX_FRAMES) {
                        throw new BusinessException(ErrorCode.UNPROCESSABLE, "视频拼接时长异常，已中止");
                    }
                }

                try {
                    grabber.release();
                } catch (Exception ignored) {
                    // release 失败不阻断（继续下一段）
                }
                grabber = null;
            }

            long size = Files.size(tempFile);
            long costMs = System.currentTimeMillis() - started;
            log.info("canvas concat done: segments={} totalDurationMs={} costMs={} size={}",
                    parts.size(), totalDurationUs / 1_000, costMs, size);
            return new ConcatResult(tempFile, "video/mp4", size, totalDurationUs / 1_000, parts.size());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("canvas concat failed: segments={} err={}", parts.size(), e.getMessage());
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "视频拼接失败，请稍后重试");
        } finally {
            if (recorderStarted && recorder != null) {
                try {
                    recorder.stop();
                } catch (Exception ignored) {
                    // 停录失败不阻断
                }
                try {
                    recorder.release();
                } catch (Exception ignored) {
                    // release 失败不阻断主流程
                }
            }
            if (grabber != null) {
                try {
                    grabber.release();
                } catch (Exception ignored) {
                    // release 失败不阻断主流程
                }
            }
        }
    }
}
