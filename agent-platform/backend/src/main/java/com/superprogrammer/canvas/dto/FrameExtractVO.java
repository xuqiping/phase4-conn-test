package com.superprogrammer.canvas.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 视频抽帧响应（plan C11）。返抽出的图片 fileId（落 stored_files SOURCE_CANVAS），
 * 前端据此新建图片节点 + 自动连回 {@link #sourceNodeId}（视频节点）。
 *
 * <p>失败不产空节点（plan 边界）：抽帧抛 → 端点直接返错误，不进此 VO。
 */
@Data
@Builder
public class FrameExtractVO {

    /** 抽出图片 → stored_files.file_id（新产，不覆盖源视频）。 */
    private String fileId;
    /** 预览 URL（/api/files/{fileId}，需 auth，前端 axios 拉 blob）。 */
    private String url;
    /** image/jpeg。 */
    private String mime;
    /** 字节大小。 */
    private long size;
    /** 源视频节点 id（前端建 image 节点后自动连边 video→image）。 */
    private String sourceNodeId;
}
