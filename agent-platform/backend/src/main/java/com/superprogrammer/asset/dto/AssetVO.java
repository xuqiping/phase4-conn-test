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
    /** 处理类别（V60 TEXT/IMAGE/VIDEO/AUDIO；决定前端编辑器/预览链路）。 */
    private String mediaCategory;
    private String name;
    private String description;
    private List<String> tags;
    /** 叙事角色键（双轴轴B，批查组装防 N+1）。 */
    private List<String> roleKeys;
    private String status;
    /** 正文 JSON（仅详情返回；列表省略）。 */
    private String content;
    /**
     * 文本类正文片段（S16 Bug④，列表态卡片封面用）。
     * 仅 TEXT 类别资产填充（按键优先级 body/synopsis/prompt 抽取，≤120 字去换行）；
     * 列表态独立于 {@link #content} 懒加载，列表也能拿正文片段不拉全文。
     */
    private String textPreview;
    /** 生成谱系 JSON（含技术元数据：宽高/时长/分辨率）。 */
    private String genMeta;
    private Integer currentVersion;
    /** 当前版本文件 id（文件类资产）。 */
    private String fileId;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
