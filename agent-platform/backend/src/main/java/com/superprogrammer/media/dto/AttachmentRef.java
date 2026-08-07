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
}
