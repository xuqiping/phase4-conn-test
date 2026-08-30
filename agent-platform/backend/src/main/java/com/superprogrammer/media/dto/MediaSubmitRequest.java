package com.superprogrammer.media.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 视频生成提交请求。
 *
 * <p>参数对齐 SeedDance 2.0 官方契约：
 * <ul>
 *   <li>{@code ratio} 画面比例，默认 16:9（controller 兜底）。</li>
 *   <li>{@code duration} 时长 4–15 秒（官方区间），默认 5。</li>
 *   <li>{@code resolution} 分辨率 480p/720p/1080p/4K，默认 720p。</li>
 *   <li>{@code watermark} 水印开关，默认 false。</li>
 *   <li>{@code generateAudio} 生成原生音频开关，默认 false。</li>
 * </ul>
 *
 * <p>图生视频（IMAGE2VIDEO）的参考图先经 {@code POST /api/files} 上传拿 fileId，
 * 再填 {@code refFileId} 提交——复用单一上传咽喉点（归属校验集中），不在本端点重做 multipart。
 */
@Data
public class MediaSubmitRequest {

    /**
     * 提示词。普通生成 / context-ir 必填（service 侧校验空即拒）；
     * regeneration 再生成必传空/null（输入只有源任务 id）——HHX-10 起 @NotBlank 下放 service 分流校验。
     */
    private String prompt;

    /** 画面比例（官方 ratio），默认 16:9。 */
    private String ratio;

    /** 时长秒，4–15（官方区间），默认 5。 */
    @Min(value = 4, message = "时长至少 4 秒")
    @Max(value = 15, message = "时长至多 15 秒")
    private Integer duration;

    /** 分辨率：480p / 720p / 1080p / 4K，默认 720p。 */
    private String resolution;

    /** 是否加水印，默认 false。 */
    private Boolean watermark;

    /** 是否同步生成原生音频（2.0 特色），默认 false。 */
    private Boolean generateAudio;

    /** TEXT2VIDEO / IMAGE2VIDEO，默认 TEXT2VIDEO。 */
    private String taskType;

    /** 图生视频参考图 stored_files.file_id（IMAGE2VIDEO 必填）。 */
    private String refFileId;

    /**
     * 参考帧位置（仅 IMAGE2VIDEO + refFileId 通道）：{@code "first"} 首帧 / {@code "last"} 尾帧。
     * null / "first" = 首帧（默认，向后兼容）；"last" = 尾帧（SeedDance 2.0）。
     */
    private String frameRole;

    /** Ark 模型 id（可选，默认取视频 provider 首个模型）。 */
    private String model;

    /**
     * 多模态参考附件（图/视频/音频，先经 /api/files/upload 拿 fileId）。
     * 上限按所选模型能力校验（如 SeedDance 2.0：9图/3视频/3音频/总≤12）。
     * 与 {@code refFileId} 互斥（refFileId 是旧版单首帧图通道，保留兼容）。
     */
    @Valid
    @Size(max = 12, message = "附件总数不能超过 12 个")
    private List<AttachmentRef> attachments;

    /** 计划5 Step5：组池计费归属（null=个人钱包）；须为本人可见的项目组成员。 */
    private Long projectGroupId;

    /**
     * HHX-10：2K 再生成源任务（平台 media_gen_tasks.id；仅 model 后缀 -regeneration 时有效）。
     * 源任务须：存在 / 本人（admin 旁路）/ SUCCEEDED / 同 provider 非附属模型 / 提交未满 7 天。
     */
    private Long sourceTaskId;
}
