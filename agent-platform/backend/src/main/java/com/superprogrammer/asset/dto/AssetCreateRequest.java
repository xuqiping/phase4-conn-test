package com.superprogrammer.asset.dto;

import lombok.Data;

import java.util.List;

/**
 * 资产创建请求（文本类：提示词/剧本，FR-003/004）。
 *
 * <p>文件类资产（图片/视频/音频）走上传端点，不经此入口。
 * mediaType=PROMPT/SCRIPT 时 content 必填（资产正文 JSON）。
 * roleKeys 可多挂（双轴矩阵轴B，受控词汇内）。
 */
@Data
public class AssetCreateRequest {

    /** 内容类型：PROMPT/SCRIPT（文本类经此入口）。 */
    private String mediaType;

    /** 资产名（必填，≤100）。 */
    private String name;

    /** 描述（可选）。 */
    private String description;

    /** 标签数组（自由层）。 */
    private List<String> tags;

    /** 叙事角色键数组（双轴轴B，多挂）。 */
    private List<String> roleKeys;

    /** 资产正文 JSON（文本类必填）。 */
    private String content;
}
