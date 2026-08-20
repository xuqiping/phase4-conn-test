package com.superprogrammer.projectgroup.dto;

import java.time.OffsetDateTime;

/** 公共池入组申请行 VO（17x#4）：组长审批列表与「我的申请」共用，人名/组名服务端补齐。 */
public record ProjectGroupJoinRequestVO(
        Long id,
        Long groupId,
        String groupName,
        Long userId,
        String username,
        String message,
        /** PENDING/APPROVED/REJECTED/REVOKED。 */
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime decidedAt) {
}
