package com.superprogrammer.projectgroup.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 我的组列表行（前端选择器数据源，计划5 Step3）。
 * myRole=OWNER/MEMBER；myQuota null=不限；balance=组池余额。
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
        int memberCount,
        OffsetDateTime createdAt) {
}
