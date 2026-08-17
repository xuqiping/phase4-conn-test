package com.superprogrammer.canvas.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 图片翻转/旋转响应（2x 四轮 S6 / spec §6.1）。同 {@link ImageCropVO} 范式：
 * 返新图 fileId（落 stored_files SOURCE_CANVAS，源图不可变），前端据此建衍生图节点 + 自动连边。
 */
@Data
@Builder
public class ImageTransformVO {

    /** 变换后图片 → stored_files.file_id（新产，不覆盖源图）。 */
    private String fileId;
    /** 预览 URL（/api/files/{fileId}，需 auth，前端 axios 拉 blob 转 objectURL）。 */
    private String url;
    /** image/png（无损，避免二次 JPEG 压缩）。 */
    private String mime;
    /** 字节大小。 */
    private long size;
    /** 源图节点 id（前端建衍生图节点后自动连边 源→衍生）。 */
    private String sourceNodeId;
    /** 实际执行的变换类型（回显，前端节点命名/审计用）。 */
    private String op;
}
