package com.superprogrammer.asset.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 资产视图。
 *
 * <p>列表态省略 {@link #content}（按需单取，plan 坑点：列表不带回大文本）。
 * {@link #fileId} = 当前版本文件 id（文件类资产）。
 */
@Data
@Builder
public class AssetVO {

    private Long id;
    private Long projectId;
    private String mediaType;
    private String name;
    private String description;
    private List<String> tags;
    /** 叙事角色键（双轴轴B，批查组装防 N+1）。 */
    private List<String> roleKeys;
    private String status;
    /** 正文 JSON（仅详情返回；列表省略）。 */
    private String content;
    /** 生成谱系 JSON（含技术元数据：宽高/时长/分辨率）。 */
    private String genMeta;
    private Integer currentVersion;
    /** 当前版本文件 id（文件类资产）。 */
    private String fileId;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
