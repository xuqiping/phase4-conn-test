package com.superprogrammer.projectgroup.dto;

import lombok.Data;

import java.util.Map;

/** 组可见性设置请求（17x#2，PUT /project-groups/{id}/visibility）。null 字段=不动。 */
@Data
public class ProjectGroupVisibilityUpdateRequest {

    /** 成员产出可见性：OWN=成员仅看自己；ALL=成员互见全组。null=不动。 */
    private String memberOutputVisibility;

    /** 按模块稀疏覆盖（key=CHAT/EMBED/RERANK/IMAGE/VIDEO，value=OWN/ALL）；null=不动；空 map=清空覆盖。 */
    private Map<String, String> moduleVisibilityOverrides;
}
