package com.superprogrammer.media.reverse.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 视频反推配置（运维开关 + 三重预算钳制，plan 运维清单「容量/性能」+ 坑表「帧数=token 成本」）。
 *
 * <p>前缀 {@code media.reverse}（与 {@code media.edit} 剪辑配置隔离，互不污染）：
 * <ul>
 *   <li>{@code enabled}(默认 true) 总开关——出问题可关入口不回滚发版（plan 配置开关）。</li>
 *   <li>{@code ffmpeg-path}：二进制路径默认走 PATH，与 media.edit 同口径（probe 用 {@code ffmpeg -i}，无需 ffprobe）。</li>
 *   <li>三重预算：时长上限 {@code max-duration-seconds}(600=10min) / 帧数上限 {@code max-frames-cap}(24) /
 *       缩略长边 {@code thumb-max-edge}(1024)——防大视频烧 CPU、帧图烧 token。</li>
 *   <li>{@code process-timeout-seconds}(60)：单次 FFmpeg 进程超时，超时 destroyForcibly 防僵尸（plan 坑表「FFmpeg 同步占 CPU」）。</li>
 *   <li>{@code max-concurrency}(2)：抽帧并发信号量上限——同步接口防 FFmpeg 并发堆积。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "media.reverse")
public class MediaReverseProperties {

    /** 总开关。false 时 extractKeyFrames 直接拒绝（功能降级，不回滚发版）。 */
    private boolean enabled = true;

    /** FFmpeg 二进制路径，默认走 PATH（部署机须装 FFmpeg，与 media.edit 同机依赖）。 */
    private String ffmpegPath = "ffmpeg";

    /** 场景检测阈值默认值（plan 坑表「阈值敏感」：默认 0.3，请求可调 [0.1,0.9]）。 */
    private double sceneThreshold = 0.3;

    /** 命中数低于此值回退均匀采样兜底（spec §4.1「命中 0 或 <4 时回退」）。 */
    private int minFrames = 4;

    /** 请求未指定 maxFrames 时的默认值（token 预算保护，spec §4.1 默认 12）。 */
    private int defaultMaxFrames = 12;

    /** maxFrames 硬上限（请求更大值被钳到此处；spec §4.1 上限 24）。 */
    private int maxFramesCap = 24;

    /** 视频时长上限（秒，plan 安全清单「时长≤10min」）。 */
    private int maxDurationSeconds = 600;

    /** 单次 FFmpeg 进程超时（秒），超时 destroyForcibly。 */
    private long processTimeoutSeconds = 60;

    /** 入 LLM 的缩略帧长边上限（像素；原始帧另存供查看，plan 坑表「token 成本」）。 */
    private int thumbMaxEdge = 1024;

    /** 抽帧并发信号量上限（同步接口防 FFmpeg 堆积）。 */
    private int maxConcurrency = 2;
}
