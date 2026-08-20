package com.superprogrammer.projectgroup.dto;

import lombok.Data;

/** 公共池入组申请请求（17x#4，POST /project-groups/{id}/join-requests）。 */
@Data
public class ProjectGroupJoinApplyRequest {

    /** 申请留言（可选，≤200）。 */
    private String message;
}
