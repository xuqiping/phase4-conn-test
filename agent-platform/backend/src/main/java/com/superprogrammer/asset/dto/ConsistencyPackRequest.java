package com.superprogrammer.asset.dto;

import lombok.Data;

import java.util.List;

/**
 * 一致性资产包请求（plan §S5 / FR-007，设计方案 §五）。
 *
 * <p>人物/道具/场景三类资产的「定妆档案」，生成时整套注入保形象一致（对标 LTX Elements / Katalist）。
 * 落资产 {@code content} JSON 的 {@code consistency} 键下（与提示词正文/剧本分场同列并存，互不覆盖）。
 *
 * <p>字段（设计方案 §五）：
 * <ul>
 *   <li>{@link #mainRefImageFileId} 主参考图（cast 定装照，换它=换演员）</li>
 *   <li>{@link #galleryFileIds} 多角度图集（正/侧/背/表情）</li>
 *   <li>{@link #standardDescription} 标准描述片段（供提示词变量槽注入）</li>
 *   <li>{@link #paramBaseline} 生成参数基线（model/seed/风格词，可空）</li>
 * </ul>
 * 任一字段为 null = 不修改该字段（局部更新语义）；整包写入会产新版本（一致性变更=版本事件）。
 */
@Data
public class ConsistencyPackRequest {

    /** 主参考图 stored_files.file_id（null=不改）。 */
    private String mainRefImageFileId;

    /** 多角度图集 file_id 列表（null=不改；空列表=清空）。 */
    private List<String> galleryFileIds;

    /** 标准描述片段（null=不改；空串=清空）。 */
    private String standardDescription;

    /** 生成参数基线 JSON 字符串（model/seed/风格词，null=不改）。 */
    private String paramBaseline;
}
