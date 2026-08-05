package com.superprogrammer.asset.dto;

import lombok.Data;

/**
 * 项目创建请求（FR-001）。
 * name 必填（≤100，安全清单）；description 可选。
 * narrative_roles 创建时默认五桶，不在此传（由 owner/editor 后续维护）。
 */
@Data
public class ProjectCreateRequest {

    /** 项目名（必填，≤100）。 */
    private String name;

    /** 项目描述（可选）。 */
    private String description;
}
