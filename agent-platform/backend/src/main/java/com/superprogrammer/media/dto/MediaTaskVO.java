package com.superprogrammer.media.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 媒体生成任务视图（前端轮询/列表用）。
 *
 * <p>{@code videoUrl} 仅 SUCCEEDED 且当前用户有归属时非空——指向下载端点（Content-Disposition 附件），
 * 不直接暴露 Ark 临时 URL（已过期/不归属）。
 */
@Data
@Builder
public class MediaTaskVO {

    private Long id;
    private String status;
    private String statusFlag;
    private String taskType;
    private String model;
    private String prompt;
    private Integer duration;
    private String resolution;
    private Integer tokensCost;
    private String errorMsg;
    /** 下载端点（仅 SUCCEEDED 且有归属）。 */
    private String videoUrl;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
