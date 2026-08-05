package com.superprogrammer.asset.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 资产版本视图（plan §S5 / FR-006）。
 *
 * <p>列表态省略 {@link #content}（meta only，plan 坑点：版本列表不带回大文本，列表响应 <300ms），
 * 仅 {@link AssetVersionService#getVersion} 单取时填充。
 */
@Data
@Builder
public class VersionVO {

    private Long id;
    private Long assetId;
    private Integer version;
    /** 该版本文件 id（文件类资产）。 */
    private String fileId;
    /** 改版说明（可选）。 */
    private String changeNote;
    /** 正文快照 JSON（仅单取返回；列表省略）。 */
    private String content;
    private Long createdBy;
    private OffsetDateTime createdAt;
}
