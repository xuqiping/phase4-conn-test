package com.superprogrammer.media.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 多模态参考附件（图/视频/音频），指向 stored_files.file_id（先经 /api/files/upload 上传）。
 */
@Data
public class AttachmentRef {

    /** stored_files.file_id。 */
    @NotBlank(message = "附件 fileId 不能为空")
    private String fileId;

    /** 附件类型：image / video / audio。 */
    @NotBlank(message = "附件类型不能为空")
    private String kind;

    /**
     * 参考帧角色（仅 kind=image 有意义）：{@code first_frame} 首帧 / {@code last_frame} 尾帧。
     * null/省略 → 普通参考图（role:reference_image）。
     * SeedDance 2.0 content[] 允许每图独立 role，故首/尾/参考三态统一走 attachments 通道，
     * 一次请求可含 1 首帧 + 1 尾帧 + N 参考图（service 校验全局 ≤1 first/≤1 last）。
     * video/audio 附件此字段忽略（恒为 reference_video/reference_audio）。
     */
    private String frameRole;
}
