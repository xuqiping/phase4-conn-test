package com.superprogrammer.media.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

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

    /** 附件级参考帧 role（SeedDance 2.0 content[] 枚举）：首帧 / 尾帧；null=reference_image。 */
    public static final String FRAME_FIRST = "first_frame";
    public static final String FRAME_LAST = "last_frame";

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

    /** 旧版参考图原始 fileId，仅用于脱敏快照追溯，不进入 Provider body。 */
    private String refFileId;

    /**
     * 参考帧位置（仅 IMAGE2VIDEO + refImageUrl 通道用）：{@code "first"} 首帧 / {@code "last"} 尾帧。
     * nullable / "first" → Ark 裸 image_url（首帧语义，旧版默认，向后兼容）；
     * "last" → role:{@code last_frame}（SeedDance 2.0 尾帧）。provider 拥有 Ark role 字符串映射，
     * 对外只暴露 first/last 两个通用值。
     */
    private String frameRole;

    /**
     * 多模态参考附件（已解析为 data URI，image/video/audio）。
     * nullable：纯文生视频 / 旧版 refImageUrl 首帧路径不带。
     * 与 refImageUrl 互斥（提交侧已校验）。
     */
    private List<ResolvedAttachment> attachments;

    /** 所属 llm_providers.id（多 MEDIA provider 路由用：create/query 按任务落库时的 provider 走）。 */
    private Long providerId;

    /** 已解析的参考附件（kind=image/video/audio，dataUri 可直接喂 Ark content 项）。 */
    @Data
    @Builder
    public static class ResolvedAttachment {
        /** 原始 stored_files.file_id，仅用于脱敏快照追溯。 */
        private String fileId;
        private String kind;
        private String dataUri;
        /** 参考帧角色（仅 image）：first_frame/last_frame/null(=reference_image)。透传自 AttachmentRef。 */
        private String frameRole;
    }
}
