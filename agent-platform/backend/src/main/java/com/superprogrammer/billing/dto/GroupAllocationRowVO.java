package com.superprogrammer.billing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 项目组分配视图行（D3 · 20x-2）：admin 看「每个用户在每个组里拿到多少额度、花掉多少」。
 * <p>行源 = project_group_members 活行（含组长行，组长 quota=NULL=组池直管，毛额/净额为 0）。
 * <p>毛额/净额口径（A 计划 MEMBER_* 流水，ref_id=成员 userId）：
 * 累计被分配 gross=Σ MEMBER_ALLOCATE.delta；收回 reclaimed=Σ −MEMBER_RECLAIM.delta（流水存负数取反）；
 * 净额 net=gross−reclaimed。MEMBER_QUOTA_ADJUST（delta=0 边界留痕）不入聚合。
 */
public record GroupAllocationRowVO(Long groupId,
                                   String groupName,
                                   Long userId,
                                   String username,
                                   String name,
                                   String remark,
                                   String role,
                                   BigDecimal quotaLimit,
                                   BigDecimal usedPoints,
                                   BigDecimal remaining,
                                   BigDecimal totalAllocated,
                                   BigDecimal reclaimed,
                                   BigDecimal netAllocated,
                                   OffsetDateTime lastAllocatedAt) {
}
