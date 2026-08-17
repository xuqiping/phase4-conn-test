package com.superprogrammer.media.reverse.dto;

import lombok.Data;

import java.util.List;

/**
 * 反推分析请求（spec §4.1）。源 taskId/fileId 二选一：历史任务走 loadForDownload 咽喉取产物文件，
 * fileId 走文件访问授权（抽帧 loadPath 同口径）。
 *
 * <p>扁平化偏差：spec 原文嵌套 {@code {source:{taskId,fileId}}}，此处平铺两字段（等价、前端更直写）。
 * {@code model} 为实施新增（spec 未列）：多模态分析需显式指定视觉模型时空值回退管理员默认对话模型。
 */
@Data
public class ReverseAnalyzeRequest {

    /** 源一：历史媒体任务 id（与 fileId 二选一）。 */
    private Long taskId;

    /** 源二：已上传/画布视频文件 id（与 taskId 二选一）。 */
    private String fileId;

    /** 产物组合：KEYFRAMES / STORYBOARD / SCRIPT 非空子集。 */
    private List<String> modes;

    /** 场景检测阈值，默认 0.3，钳制 [0.1,0.9]。 */
    private Double sceneThreshold;

    /** 期望帧数，默认 12，钳制 [4,24]。 */
    private Integer maxFrames;

    /** 可选：多模态分析模型（空=管理员默认对话模型）。 */
    private String model;

    /** 可选：参与项目组 id（组池计费，usage_log 落 project_group_id）。 */
    private Long projectGroupId;
}
