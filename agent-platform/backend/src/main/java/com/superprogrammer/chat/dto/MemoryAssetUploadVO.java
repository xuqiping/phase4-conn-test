package com.superprogrammer.chat.dto;

import lombok.Data;

/**
 * 聊天附件上传结果（V69 记忆二期 P3，FR-201）。
 * 一文件一记忆：memoryId 即 memory_asset_memories 行 id（PROCESSING → worker 异步 READY/FAILED）。
 */
@Data
public class MemoryAssetUploadVO {

    private Long memoryId;
    private String fileId;
    private String originalName;
    private String fileKind;
    private Long size;
    private String ingestStatus;
}
