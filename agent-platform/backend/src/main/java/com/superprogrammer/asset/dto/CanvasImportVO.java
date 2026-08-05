package com.superprogrammer.asset.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 画布产出入库结果 VO（plan §S7 / FR-008）。
 *
 * <p>三态：
 * <ul>
 *   <li>{@code created=true}：已落新资产/新版本（assetId/version 为新落点）</li>
 *   <li>{@code created=false} + {@code duplicateAssetId}!=null：节点已入库，前端弹「存为新版本/新建」二选一</li>
 * </ul>
 */
@Data
@Builder
public class CanvasImportVO {

    /** 资产 id（新建/新版本所属资产）。 */
    private Long assetId;

    /** 资产名。 */
    private String name;

    /** 内容类型：PROMPT/SCRIPT/IMAGE/VIDEO/AUDIO（据节点类型映射）。 */
    private String mediaType;

    /** 落点版本号。 */
    private Integer version;

    /** 是否本次实际创建/建版（false=检测到重复未落）。 */
    private boolean created;

    /** 重复入库时已存在的资产 id（前端提示用）。 */
    private Long duplicateAssetId;

    /** 重复入库时该资产当前版本号。 */
    private Integer duplicateVersion;

    /** 人类可读结果说明（成功/重复提示）。 */
    private String message;
}
