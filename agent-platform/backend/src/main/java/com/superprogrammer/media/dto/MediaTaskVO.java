package com.superprogrammer.media.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 媒体生成任务视图（前端轮询/列表用）。
 *
 * <p>{@code videoUrl} 仅 SUCCEEDED 且当前用户有归属时非空——指向下载端点（Content-Disposition 附件），
 * 不直接暴露 Ark 临时 URL（已过期/不归属）。
 *
 * <p>图片任务（TEXT2IMAGE/IMAGE2IMAGE）一次返 N 张：{@code imageUrls} 为各张下载端点列表，
 * {@code generatedImages}/{@code outputTokens} 来自 result_meta；视频任务这些字段为 null。
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
    /**
     * 结果文件 stored_files.file_id（仅 SUCCEEDED 且有归属）。
     * C11 抽帧用：画布视频节点存此 fileId，VideoFrameService.loadPath 直读做 javacv seek（videoUrl 是下载端点非 fileId）。
     * 与 videoUrl 同归属门控；/api/files/{id} load() 仍会复检 ownership。
     */
    private String resultFileId;
    /** 图片任务：各张下载端点列表（同 videoUrl 归属门控）。视频任务 null。 */
    private List<String> imageUrls;
    /**
     * 图片任务：各张 stored_files.file_id 列表（同 imageUrls 归属门控）。视频任务 null。
     * 画布图片节点 AI 生图用：存首张 fileId 作节点 fileId，焦点编辑裁剪据此 loadPath 取源图（同视频 resultFileId 范式）。
     */
    private List<String> imageFileIds;
    /** 图片任务：官方 generated_images（计费张数）。 */
    private Integer generatedImages;
    /** 图片任务：usage.output_tokens（审计）。 */
    private Long outputTokens;
    /** 图片任务回显：size。 */
    private String size;
    /** 图片任务回显：输出格式。 */
    private String outputFormat;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
