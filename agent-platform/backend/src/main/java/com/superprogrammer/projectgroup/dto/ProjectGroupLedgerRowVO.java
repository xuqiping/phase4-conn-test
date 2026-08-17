package com.superprogrammer.projectgroup.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 组池流水行 VO（计划5 Step7 组长总览）。
 * actorUsername 由 service 批量补齐（用户已删返 null，前端显「#uid」）。
 */
public record ProjectGroupLedgerRowVO(
        Long id,
        OffsetDateTime createdAt,
        Long actorUserId,
        String actorUsername,
        String type,
        BigDecimal deltaPoints,
        BigDecimal balanceAfter,
        String refType,
        String refId,
        String remark) {
}
