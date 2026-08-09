package com.superprogrammer.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件分块视图（二期 P3 Step 5，FR-203 文件卡片「展开分块」数据源）。
 * 仅 owner 可读（附件记忆是个人域资产）；pageRef 为页码锚点（PDF/PPT 逐页 chunk）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileChunkView {

    private Integer chunkNo;
    private String pageRef;
    private String chunkText;
}
