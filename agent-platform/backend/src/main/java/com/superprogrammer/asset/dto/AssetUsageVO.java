package com.superprogrammer.asset.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 资产「使用记录」VO（plan §S7 / FR-011，设计方案 §八「双向追溯」）。
 *
 * <p>资产详情页使用记录列表的一行：该资产被哪个画布的哪个节点引用（REFERENCE）
 * 或产自哪个画布节点（PRODUCED）。来源=asset_bindings 表。
 */
@Data
@Builder
public class AssetUsageVO {

    /** 绑定记录 id。 */
    private Long id;

    /** 引用/产出的版本快照号（锁定版本，可空）。 */
    private Integer assetVersion;

    /** 关联画布 id。 */
    private Long canvasId;

    /** 关联画布节点 id。 */
    private String nodeId;

    /** 绑定类型：REFERENCE(被节点引用) / PRODUCED(产自节点)。 */
    private String bindType;

    /** 绑定操作人 userId（审计）。 */
    private Long createdBy;

    /** 绑定时间。 */
    private OffsetDateTime createdAt;
}
