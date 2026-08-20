package com.superprogrammer.projectgroup.dto;

import java.time.OffsetDateTime;

/** 公共池招募中的项目组行（17x#4，GET /project-groups/pool）。 */
public record ProjectGroupPoolItemVO(
        Long id,
        String name,
        String description,
        String ownerUsername,
        Long memberCount,
        OffsetDateTime publishedAt,
        /** 当前用户是否已是成员（含组长）。 */
        Boolean alreadyMember,
        /** 当前用户在该组的申请状态（PENDING/...；无申请 null）。 */
        String myRequestStatus) {
}
