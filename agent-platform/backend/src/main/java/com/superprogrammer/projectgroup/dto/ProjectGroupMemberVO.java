package com.superprogrammer.projectgroup.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 组成员行（管理页）：quota null=不限；used=已耗快照；role=OWNER/MANAGER/MEMBER（V139）；allowedKinds null=不限；memberVisibilityOverrides null=无覆盖（V139）。 */
public record ProjectGroupMemberVO(
        Long userId,
        String username,
        String displayName,
        boolean owner,
        String role,
        List<String> allowedKinds,
        Map<String, String> memberVisibilityOverrides,
        BigDecimal quotaLimitPoints,
        BigDecimal usedPoints,
        OffsetDateTime joinedAt) {
}
