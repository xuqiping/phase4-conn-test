package com.superprogrammer.projectgroup.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 组邀请行 VO（17x#3）：被邀请人「我的邀请」与组长「邀请管理」共用，人名/组名服务端补齐。 */
public record ProjectGroupInviteVO(
        Long id,
        Long groupId,
        String groupName,
        Long inviterUserId,
        String inviterName,
        Long inviteeUserId,
        String inviteeName,
        BigDecimal quotaLimitPoints,
        /** PENDING/ACCEPTED/DECLINED/CANCELED。 */
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime decidedAt) {
}
