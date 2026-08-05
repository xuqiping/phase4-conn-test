package com.superprogrammer.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 资产↔叙事角色 多对多关联（asset_role_links，V57）。
 *
 * <p>双轴矩阵 轴B 挂载：一资产可挂多叙事角色（"多对多非单父"，设计方案 §二）。
 * 角色过滤走本关系表（不查 JSONB，plan 坑点预判）。
 *
 * <p>轻量关联表：不继承 BaseEntity，硬删行（UNIQUE(asset_id, role_key) 去重）。
 */
@Data
@TableName("asset_role_links")
public class AssetRoleLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属资产（FK assets）。 */
    private Long assetId;

    /** 叙事角色键（取值来自 asset_projects.narrative_roles 受控词汇）。 */
    private String roleKey;

    private OffsetDateTime createdAt;
}
