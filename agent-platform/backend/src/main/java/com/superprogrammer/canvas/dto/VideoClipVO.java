package com.superprogrammer.canvas.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 视频截取响应（plan C12）。返截取出的视频 fileId（落 stored_files SOURCE_CANVAS），
 * 前端据此新建视频节点 + 自动连回 {@link #sourceNodeId}（源视频节点）。
 *
 * <p>失败不产空节点（plan 边界）：截取抛 → 端点直接返错误，不进此 VO。
 */
@Data
@Builder
public class VideoClipVO {

    /** 截出视频 → stored_files.file_id（新产，不覆盖源视频）。 */
    private String fileId;
    /** 预览 URL（/api/files/{fileId}，需 auth，前端 axios 拉 blob 转 objectURL）。 */
    private String url;
    /** video/mp4。 */
    private String mime;
    /** 字节大小。 */
    private long size;
    /** 源视频节点 id（前端建视频节点后自动连边 video→video）。 */
    private String sourceNodeId;
}
