package com.superprogrammer.projectgroup.dto;

import lombok.Data;

/** 建组请求（计划5 Step3）。name 必填 ≤64 字；description ≤500。 */
@Data
public class ProjectGroupCreateRequest {
    private String name;
    private String description;
}
