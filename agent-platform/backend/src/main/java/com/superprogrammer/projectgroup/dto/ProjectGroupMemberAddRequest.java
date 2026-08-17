package com.superprogrammer.projectgroup.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 加成员请求：userId 必填；quotaLimitPoints null=不限。 */
@Data
public class ProjectGroupMemberAddRequest {
    private Long userId;
    private BigDecimal quotaLimitPoints;
}
