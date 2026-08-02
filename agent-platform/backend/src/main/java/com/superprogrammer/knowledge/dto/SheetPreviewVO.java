package com.superprogrammer.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Excel sheet 预读结果（阶段1）。
 * tempFileRef = 阶段1 存的文件引用（= /api/files/{fileId}），阶段2 upload 复用，不重传文件。
 */
@Data
@Builder
public class SheetPreviewVO {

    /** 临时文件引用（复用 token），阶段2 upload 传回。归属已在 store 时记入 stored_files。 */
    private String tempFileRef;

    /** 原始文件名（前端展示）。 */
    private String fileName;

    /** 非隐藏 sheet 名列表（受 preview-max-sheets 截断）。 */
    private List<String> sheetNames;
}
