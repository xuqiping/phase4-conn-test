package com.superprogrammer.asset.dto;

import lombok.Data;

/**
 * 媒体生成产物入库请求（POST /api/assets/from-media）。
 *
 * <p>把生图任务的某张结果图存入资产库（生成→库）。与 {@link CanvasImportRequest}（画布→库）并列，
 * 但不依赖画布节点/canvasId——直接按 taskId + imageIdx 定位 stored_files 行复用（SOURCE_MEDIA，不拷贝）。
 *
 * <p>projectId 必填（资产库无个人域，资产必属某项目；{@code AssetAclService.requireWrite} 校验可写）。
 */
@Data
public class MediaImportRequest {

    /** 生图/视频任务 id（media_gen_tasks.id）。 */
    private Long taskId;
    /**
     * 产物类型（4x-2）：IMAGE=生图逐张入库（按 imageIdx 定位）；VIDEO=视频任务结果入库
     * （按 result_file_id 定位，无需 imageIdx）。空值按 IMAGE 兼容旧调用方。
     */
    private String mediaKind;
    /** 入库的目标图下标（0-based，对应该任务 result_meta.imageFileIds 顺序；仅 IMAGE 用）。 */
    private Integer imageIdx;
    /** 目标项目 id（必填，须当前用户可写）。 */
    private Long projectId;
    /** 资产名（≤100；空则由桥兜底「图片产出」）。 */
    private String name;
    /** 资产描述。 */
    private String description;
}
