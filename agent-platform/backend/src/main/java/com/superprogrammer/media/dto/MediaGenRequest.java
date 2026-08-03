package com.superprogrammer.media.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 媒体生成请求（任务型，统一 video/image）。
 *
 * <p>与 {@code LlmRequest}（同步/流式文本协议）刻意分离：SeedDance 走 Ark 任务端点
 * （建任务→轮询→取结果），协议本质不同，不塞进 chat provider。
 *
 * <p>{@code refImageUrl} 仅 IMAGE2VIDEO 用（首帧参考图，Ark 临时/公网 URL 或 data: base64）。
 * {@code duration}/{@code resolution} 受 worker 配置上限校验（MVP ≤10s / ≤720p）。
 */
@Data
@Builder
public class MediaGenRequest {

    /** TEXT2VIDEO 文生视频 / IMAGE2VIDEO 图生视频。 */
    public static final String TYPE_TEXT2VIDEO = "TEXT2VIDEO";
    public static final String TYPE_IMAGE2VIDEO = "IMAGE2VIDEO";

    /** Ark 模型 id，如 doubao-seedance-2-0。 */
    private String model;

    /** 提示词（必填）。 */
    private String prompt;

    /** 时长（秒）。 */
    private Integer duration;

    /** 分辨率白名单值，如 480p / 720p。 */
    private String resolution;

    /** 帧率，默认 24。 */
    private Integer fps;

    /** 任务类型。 */
    private String taskType;

    /** 图生视频首帧图（URL 或 data URI）。nullable：文生视频不带。 */
    private String refImageUrl;
}
