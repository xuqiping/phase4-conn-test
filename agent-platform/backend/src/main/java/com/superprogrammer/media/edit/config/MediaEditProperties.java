package com.superprogrammer.media.edit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 视频剪辑配置（运维开关 + 上限 + FFmpeg 路径，plan 安全/性能清单）。
 *
 * <p>前缀 {@code media.edit}（与 {@code media.*} 生成配置隔离，互不污染）：
 * <ul>
 *   <li>{@code media.edit.enabled}(默认 true) 总开关；false 时 submit 直接拒绝（功能降级）。</li>
 *   <li>{@code media.edit.ffmpeg-path / ffprobe-path}：二进制路径，默认走 PATH（部署机须装 FFmpeg）。</li>
 *   <li>{@code media.edit.font-file}：drawtext 字体路径，跨 OS 必须显式配（缺则降级跳过字幕）。</li>
 *   <li>{@code media.edit.max-duration / max-resolution / max-clips}：盘与成本上限。</li>
 *   <li>{@code media.edit.poll-ms / lock-minutes / task-timeout-seconds}：worker 轮询/认领锁/单任务超时。</li>
 *   <li>{@code media.edit.render-timeout-seconds}：单次 FFmpeg 进程超时（< task-timeout，防僵尸进程）。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "media.edit")
public class MediaEditProperties {

    /** 总开关。false 时 submit 直接拒绝（功能降级）。 */
    private boolean enabled = true;

    /** FFmpeg 二进制路径，默认走 PATH（probe 也用 ffmpeg -i，无需单独 ffprobe）。 */
    private String ffmpegPath = "ffmpeg";

    /**
     * drawtext 字体路径。支持「逗号分隔的多路径候选」——{@link #resolveFontFile()} 按序返回第一个存在文件；
     * 也兼容单路径（如 mac-dev.sh 经环境变量 {@code MEDIA_EDIT_FONT_FILE} 注入）。
     * <p>跨 OS 须配至少一个存在的 CJK 字体；全部解析不到且有字幕时，渲染侧 fail-fast（不再静默跳过字幕）。
     * <p>注意 ffmpeg 4.4 无法加载 {@code .ttc}，CJK 字体须用 {@code .ttf/.otf}（如 macOS 的 Arial Unicode.ttf 含中文字形）。
     */
    private String fontFile;

    /**
     * 解析字体：fontFile 为逗号分隔候选时，按序返回第一个存在的文件；未配或全部不存在返回 null。
     * 调用方对「有字幕但返回 null」应 fail-fast。
     */
    public String resolveFontFile() {
        String f = fontFile;
        if (f == null || f.isBlank()) {
            return null;
        }
        for (String candidate : f.split(",")) {
            String c = candidate.trim();
            if (!c.isEmpty() && Files.exists(Path.of(c))) {
                return c;
            }
        }
        return null;
    }

    /** 成片总时长上限（秒）。 */
    private int maxDuration = 120;
    /** 成片分辨率上限（720p/1080p…），默认 720p。 */
    private String maxResolution = "720p";
    /** 单次剪辑片段数上限。 */
    private int maxClips = 10;
    /** 单个素材时长上限（秒），防超大文件 DoS。 */
    private int maxClipSeconds = 600;
    /** 音频轨上限（含 BGM/配音等，不含原声）。 */
    private int maxAudioTracks = 4;
    /** 所有轨 segment 总数上限（防 filter_complex 过长）。 */
    private int maxSegments = 40;

    /** 剪映草稿导出：素材总大小上限（MB），超限拒（防边打包边超）。 */
    private long draftMaxTotalMb = 800;
    /** 剪映 draft_content.json 的 version 字段（明文草稿各版剪映均可导入）。 */
    private String draftJianyingVersion = "9.9.0";
    /** 剪映草稿导出是否把素材打包进 zip（true=可移植，path 写相对 ./xxx；false=写服务器绝对路径）。 */
    private boolean draftBundleMedia = true;

    /** worker 轮询间隔（ms）。 */
    private long pollMs = 5000;
    /** 认领锁时长（分钟），过期后 RUNNING 行可被重新认领（崩溃恢复）。须 > renderTimeoutSeconds/60，防长渲染中被重认领。 */
    private int lockMinutes = 10;
    /** 单任务最长等待（秒），超时置 FAILED。 */
    private long taskTimeoutSeconds = 600;
    /** 单次 FFmpeg 进程超时（秒），超时 destroyForcibly 防僵尸。 */
    private long renderTimeoutSeconds = 540;
}
