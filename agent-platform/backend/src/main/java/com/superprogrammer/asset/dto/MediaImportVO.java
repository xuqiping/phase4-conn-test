package com.superprogrammer.asset.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 媒体产物入库结果（POST /api/assets/from-media 响应）。
 *
 * <p>修复III F1（17x#1）：同项目判重——同 (projectId, taskId, imageIdx) 已入库则不重复建资产，
 * 返回 created=false + duplicate=true + 既有 assetId；不同项目仍可各自入库（同一张图存多个项目语义保留）。
 */
@Data
@Builder
public class MediaImportVO {

    /** 是否入库成功（false=命中同项目判重，复用既有资产）。 */
    private boolean created;
    /** 修复III F1：true=同项目已存在该任务产物（duplicate 分支，created=false）。 */
    @Builder.Default
    private boolean duplicate = false;
    /** 新建资产 id。 */
    private Long assetId;
    /** 资产名。 */
    private String name;
    /** 媒体类型（图片="图片"）。 */
    private String mediaType;
    /** 版本号（首版=1）。 */
    private Integer version;
    /** 提示信息。 */
    private String message;
}
