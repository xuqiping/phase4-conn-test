package com.superprogrammer.media.edit.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 视频剪辑任务视图（前端轮询/列表用）。
 *
 * <p>{@code videoUrl} 仅 SUCCEEDED 且当前用户有归属时非空——指向下载端点（Content-Disposition 附件），
 * 不暴露内部存储路径。
 */
@Data
@Builder
public class MediaEditTaskVO {

    private Long id;
    private String status;
    private String errorMsg;
    /** 片段数（解析 edit_spec.clips）。 */
    private Integer clipsCount;
    /** 是否含背景音乐。 */
    private Boolean hasBgm;
    /** 字幕条数。 */
    private Integer subtitlesCount;
    /** 下载端点（仅 SUCCEEDED 且有归属）。 */
    private String videoUrl;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
