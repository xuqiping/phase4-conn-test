package com.superprogrammer.canvas.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 焦点编辑裁剪响应。返裁剪后图片 fileId（落 stored_files SOURCE_CANVAS），
 * 前端据此新建图片节点（带 fileId+预览）+ 自动连回 {@link #sourceNodeId}（源图节点）。
 *
 * <p>失败不产空文件（plan 边界）：裁剪抛 → 端点直接返错误，不进此 VO。
 */
@Data
@Builder
public class ImageCropVO {

    /** 裁剪后图片 → stored_files.file_id（新产，不覆盖源图）。 */
    private String fileId;
    /** 预览 URL（/api/files/{fileId}，需 auth，前端 axios 拉 blob 转 objectURL）。 */
    private String url;
    /** image/png（无损裁剪，避免二次 JPEG 压缩）。 */
    private String mime;
    /** 字节大小。 */
    private long size;
    /** 源图节点 id（前端建 image 节点后自动连边 image→image）。 */
    private String sourceNodeId;
}
