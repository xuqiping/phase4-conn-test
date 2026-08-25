package com.superprogrammer.projectgroup.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 我的组列表行（前端选择器数据源，计划5 Step3）。
 * myRole=OWNER/MANAGER/MEMBER；myQuota null=不限；balance=组池余额。
 * V156：myAllocatable=我作为管理的可分配额度（仅 MANAGER 有值；null=不适用或不限）。
 */
public record ProjectGroupMineVO(
        Long id,
        String name,
        String description,
        Long ownerUserId,
        String myRole,
        BigDecimal balancePoints,
        BigDecimal myQuota,
        BigDecimal myUsed,
        BigDecimal myAllocatable,
        int memberCount,
        OffsetDateTime createdAt) {
}
