package com.superprogrammer.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 资产版本快照（asset_versions，V57）。
 *
 * <p>不可变历史记录。引用资产时锁定某版本（{@link #assetId}+{@link #version}+{@link #fileId}），
 * 资产迭代到新版不影响已引用方（版本隔离防冲突，设计方案 §六）。
 *
 * <p>轻量表：不继承 BaseEntity（无软删/乐观锁/updated 列），仅 id + 审计（created_by/at）。
 */
@Data
@TableName(value = "asset_versions", autoResultMap = true)
public class AssetVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属资产（FK assets）。 */
    private Long assetId;

    /** 版本号（从 1 递增，UNIQUE(asset_id, version)）。 */
    private Integer version;

    /** 该版本文件 stored_files.file_id（文件类资产；文本类可空，正文在 content）。 */
    private String fileId;

    /** 该版本正文快照 JSON（文本类）/一致性包快照。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String content;

    /** 改版说明（可选）。 */
    private String changeNote;

    private Long createdBy;

    private OffsetDateTime createdAt;
}
