package com.superprogrammer.asset.dto;

import lombok.Data;

/** OWNER/管理员对待审批公共访问申请作出的决定。 */
@Data
public class PublicAccessDecisionRequest {
    private String decision;
}
