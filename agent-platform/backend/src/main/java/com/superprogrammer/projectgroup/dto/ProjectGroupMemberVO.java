package com.superprogrammer.projectgroup.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 组成员行（管理页）：quota null=不限；used=已耗快照。 */
public record ProjectGroupMemberVO(
        Long userId,
        String username,
        String displayName,
        boolean owner,
        BigDecimal quotaLimitPoints,
        BigDecimal usedPoints,
        OffsetDateTime joinedAt) {
}
