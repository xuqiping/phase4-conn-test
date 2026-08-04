package com.superprogrammer.media.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 媒体生成请求（任务型，统一 video/image）。
 *
 * <p>与 {@code LlmRequest}（同步/流式文本协议）刻意分离：SeedDance 走 Ark 任务端点
 * （建任务→轮询→取结果），协议本质不同，不塞进 chat provider。
 *
 * <p>参数对齐 SeedDance 2.0 官方契约（顶层平铺，无 parameters 包裹）：
 * <ul>
 *   <li>{@code ratio} 画面比例（21:9/16:9/4:3/1:1/3:4/9:16/adaptive），默认 16:9。</li>
 *   <li>{@code duration} 时长 4–15 秒，受 worker 配置上限校验。</li>
 *   <li>{@code resolution} 分辨率 480p/720p/1080p/4K（可选，官方默认 720p）。</li>
 *   <li>{@code watermark} 是否加水印，默认 false。</li>
 *   <li>{@code generateAudio} 是否同步生成原生音频，默认 false。</li>
 * </ul>
 * 官方无 fps 参数（统一 24fps），故不再传 fps。
 *
 * <p>{@code refImageUrl} 仅 IMAGE2VIDEO 用（首帧参考图，Ark 临时/公网 URL 或 data: base64）。
 */
@Data
@Builder
public class MediaGenRequest {

    /** TEXT2VIDEO 文生视频 / IMAGE2VIDEO 图生视频。 */
    public static final String TYPE_TEXT2VIDEO = "TEXT2VIDEO";
    public static final String TYPE_IMAGE2VIDEO = "IMAGE2VIDEO";

    /** Ark 模型 id，如 doubao-seedance-2-0 / Cdance2.0。 */
    private String model;

    /** 提示词（必填）。 */
    private String prompt;

    /** 画面比例（官方 ratio），默认 16:9。 */
    private String ratio;

    /** 时长（秒），4–15。 */
    private Integer duration;

    /** 分辨率 480p/720p/1080p/4K（可选）。 */
    private String resolution;

    /** 是否加水印，默认 false。 */
    private Boolean watermark;

    /** 是否同步生成原生音频（2.0 特色），默认 false。 */
    private Boolean generateAudio;

    /** 任务类型。 */
    private String taskType;

    /** 图生视频首帧图（URL 或 data URI）。nullable：文生视频不带。 */
    private String refImageUrl;
}
