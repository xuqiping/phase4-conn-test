package com.superprogrammer.projectgroup.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** 组详情（管理页）：基本信息 + 组池/在途 + 成员列表。 */
public record ProjectGroupDetailVO(
        Long id,
        String name,
        String description,
        Long ownerUserId,
        String ownerUsername,
        BigDecimal balancePoints,
        BigDecimal inflightPoints,
        List<ProjectGroupMemberVO> members,
        OffsetDateTime createdAt) {
}
