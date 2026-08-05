package com.superprogrammer.canvas.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 故事板拼接响应（plan C13）。返成片 fileId（落 stored_files SOURCE_CANVAS），
 * 前端据此新建视频节点展示拼接结果。
 *
 * <p>失败不产空文件（plan 边界）：拼接抛 → 端点直接返错误，不进此 VO。
 */
@Data
@Builder
public class StoryboardConcatVO {

    /** 成片 → stored_files.file_id（新产）。 */
    private String fileId;
    /** 预览 URL（/api/files/{fileId}，需 auth，前端 axios 拉 blob 转 objectURL）。 */
    private String url;
    /** video/mp4。 */
    private String mime;
    /** 字节大小。 */
    private long size;
    /** 拼接段数。 */
    private int segmentCount;
    /** 成片总时长（秒）。 */
    private long totalDurationSec;
    /** 参与拼接的源视频节点 id（前端建成片节点后可批量连边，可选）。 */
    private List<String> sourceNodeIds;
}
