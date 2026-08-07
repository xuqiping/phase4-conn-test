package com.superprogrammer.media.config;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单个视频模型的能力画像（附件上限 / 参数白名单 / 特性开关）。
 *
 * <p>不同视频模型的多模态参考能力不一致（SeedDance 2.0 = 9图/3视频/3音频/总12；
 * 1.0 系列仅首帧图）。能力默认值由 {@link MediaModelCapabilityService} 按模型名前缀给出，
 * 可在「全局模型供应商」provider 的 config JSON 里按 modelId 精确覆盖。
 */
@Data
@Builder
public class MediaModelCapability {

    /** 参考图上限（0 = 不支持参考图）。 */
    private int maxImages;

    /** 参考视频上限（0 = 不支持参考视频）。 */
    private int maxVideos;

    /** 参考音频上限（0 = 不支持参考音频）。 */
    private int maxAudios;

    /** 附件总数上限（图+视频+音频合计）。 */
    private int maxAttachments;

    /** 支持的画面比例（与官方 ratio 枚举对齐）。 */
    private List<String> supportedRatios;

    /** 支持的分辨率（前端选项 + 提交校验共用）。 */
    private List<String> supportedResolutions;

    /** 时长下限（秒）。 */
    private int minDuration;

    /** 时长上限（秒）。 */
    private int maxDuration;

    /** 是否支持 generate_audio（2.0 原生音画同生）。 */
    private boolean supportsGenerateAudio;

    /**
     * 参考视频是否允许以 data URI 直传。
     * 官方 Ark 对 video_url 的 base64 支持未确认——置 false 时前端隐藏视频上传区，
     * 避免用户上传后必败。可在 provider config 里按 modelId 覆盖。
     */
    private boolean videoDataUri;
}
