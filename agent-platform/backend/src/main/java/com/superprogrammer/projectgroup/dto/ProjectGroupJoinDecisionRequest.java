package com.superprogrammer.projectgroup.dto;

import lombok.Data;

/** 公共池入组申请审批请求（17x#4，PUT /project-groups/join-requests/{id}/decision）。 */
@Data
public class ProjectGroupJoinDecisionRequest {

    /** true=通过（落成员行）；false=拒绝（30 天防刷）。 */
    private Boolean approve;
}
