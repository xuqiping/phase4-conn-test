package com.superprogrammer.projectgroup.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 组详情（管理页）：基本信息 + 组池/在途 + 成员列表。
 * V138（17x#2/#4）增：成员产出可见性设置 + 公共池状态（组长设置页渲染当前值用）。
 */
public record ProjectGroupDetailVO(
        Long id,
        String name,
        String description,
        Long ownerUserId,
        String ownerUsername,
        BigDecimal balancePoints,
        BigDecimal inflightPoints,
        List<ProjectGroupMemberVO> members,
        OffsetDateTime createdAt,
        /** 成员产出可见性（OWN/ALL，17x#2）。 */
        String memberOutputVisibility,
        /** 按模块可见性覆盖 JSON 串（17x#2；前端 JSON.parse）。 */
        String moduleVisibilityOverrides,
        /** 公共池招募开关（17x#4）。 */
        Boolean publicPool) {
}
