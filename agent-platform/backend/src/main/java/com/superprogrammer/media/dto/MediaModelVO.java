package com.superprogrammer.media.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 视频模型目录视图（GET /api/media/models）。
 *
 * <p>前端据此渲染模型下拉 + 按能力动态渲染附件上传区（图片 x/maxImages 等）。
 */
@Data
@Builder
public class MediaModelVO {

    /** 模型 id（提交时回传 model 字段）。 */
    private String modelId;

    /** 展示名（provider displayName + modelId）。 */
    private String displayName;

    /** 所属 provider name（分组显示用）。 */
    private String providerName;

    /** 是否管理员配置的全局默认视频模型（media.default.video-model；未配置/已失效无标记，
     *  前端初始选中 = defaultModel 项 || 列表第一个）。 */
    private boolean defaultModel;

    private int maxImages;
    private int maxVideos;
    private int maxAudios;
    private int maxAttachments;

    private List<String> supportedRatios;
    private List<String> supportedResolutions;
    private int minDuration;
    private int maxDuration;

    /** 是否支持「生成音频」开关。 */
    private boolean supportsGenerateAudio;

    /** 参考视频是否允许 data URI 直传（false 时前端隐藏视频上传区）。 */
    private boolean videoDataUri;

    /** 当前部署是否已配置 Ark 可访问的短期签名 HTTPS 参考视频通道。 */
    private boolean referenceVideoEnabled;
}
