package com.superprogrammer.billing.dto;

import java.math.BigDecimal;

/**
 * 组池对账原始聚合行（D4 · 20x-3，内部行——mapper 直出，service 派生 {@link GroupReconcileRowVO}）。
 * <p>各 sum 为组流水白名单类型的原始 delta 和：allocSum≥0 / reclaimSum≤0 / consumeSum≤0 / refundSum≥0
 * （写入侧约定：RECLAIM/CONSUME 存负数）。
 * <p>personalNetOut=points_ledger GROUP 腿净流出（Σ(−GROUP_ALLOCATE)+Σ(−GROUP_RECLAIM)），
 * 用于双账本交叉校验：应与组账本 netAllocated（allocSum+reclaimSum）相等。
 */
public record GroupReconcileRawVO(Long groupId,
                                  String groupName,
                                  BigDecimal balance,
                                  BigDecimal allocSum,
                                  BigDecimal reclaimSum,
                                  BigDecimal consumeSum,
                                  BigDecimal refundSum,
                                  BigDecimal personalNetOut) {
}
