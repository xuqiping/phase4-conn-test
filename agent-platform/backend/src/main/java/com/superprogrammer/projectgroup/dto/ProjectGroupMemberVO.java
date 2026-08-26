package com.superprogrammer.projectgroup.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 组成员行（管理页）：quota null=不限；used=已耗快照；role=OWNER/MANAGER/MEMBER（V139）；
 * allowedKinds null=不限；memberVisibilityOverrides null=无覆盖（V139）。
 * V156 层级额度：allocatedByUserId=预算归属上级；allocatablePoints=管理可分配额度（仅 MANAGER 行有值，null=不限/不适用）。
 * V161 欠款模型：selfPoints=组内名下余额；debtPoolPoints/debtLeaderPoints=欠款拆分（组池垫/组长垫），
 * 合计>0 时消费冻结（HOLD 拒、消费走结算）。
 */
public record ProjectGroupMemberVO(
        Long userId,
        String username,
        String displayName,
        String remark,
        boolean owner,
        String role,
        List<String> allowedKinds,
        Map<String, String> memberVisibilityOverrides,
        BigDecimal quotaLimitPoints,
        BigDecimal usedPoints,
        BigDecimal selfPoints,
        BigDecimal debtPoolPoints,
        BigDecimal debtLeaderPoints,
        Long allocatedByUserId,
        BigDecimal allocatablePoints,
        OffsetDateTime joinedAt) {
}
