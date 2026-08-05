package com.superprogrammer.asset.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建版本请求（plan §S5 / FR-006）。
 *
 * <p>文本类资产（PROMPT/SCRIPT）：{@link #content} 必填（正文快照）。
 * 文件类资产（IMAGE/VIDEO/AUDIO）：{@link #fileId} 必填（新文件 stored_files.file_id）。
 * 两类均不复制文件，fileId 复用既有 stored_files（同上传语义）。
 */
@Data
public class VersionCreateRequest {

    /** 正文快照 JSON（文本类必填）。≤8000（同安全清单提示词/剧本上限）。 */
    @Size(max = 8000)
    private String content;

    /** 该版本文件 stored_files.file_id（文件类资产换图/换视频时必填）。 */
    private String fileId;

    /** 改版说明（可选）。≤255。 */
    @Size(max = 255)
    private String changeNote;
}
