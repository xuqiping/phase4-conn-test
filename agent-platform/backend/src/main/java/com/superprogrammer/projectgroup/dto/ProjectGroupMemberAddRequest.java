package com.superprogrammer.projectgroup.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 邀请成员请求（POST /{id}/members 走邀请制）：userId 必填；quotaLimitPoints 不填=0（接受侧兜底归一，修复IV D2）。 */
@Data
public class ProjectGroupMemberAddRequest {
    private Long userId;
    private BigDecimal quotaLimitPoints;
}
