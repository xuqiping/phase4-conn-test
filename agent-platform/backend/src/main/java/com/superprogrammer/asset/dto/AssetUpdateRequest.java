package com.superprogrammer.asset.dto;

import lombok.Data;

import java.util.List;

/**
 * 资产更新请求（meta + 分类，FR-003）。
 *
 * <p>仅改描述层与分类（name/description/tags/roleKeys）。正文/文件改版走版本端点（S5）。
 */
@Data
public class AssetUpdateRequest {

    private String name;
    private String description;
    private List<String> tags;
    private List<String> roleKeys;
}
