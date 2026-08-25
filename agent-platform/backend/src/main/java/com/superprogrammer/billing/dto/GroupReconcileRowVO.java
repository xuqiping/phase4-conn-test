package com.superprogrammer.billing.dto;

import java.math.BigDecimal;

/**
 * 组池对账异常组行（D4 · 20x-3，Q9=A：仅返回异常组）。
 * <p>恒等式：期望余额 expected = 划入净额 netAllocated + 退款 refunded − 消耗 consumed；
 * 实际 = project_group_wallets.balance_points；diff = balance − expected（正=池里钱比流水算出的多）。
 * <p>crossDiff = 组账本划入净额 − 个人账本 GROUP 腿净流出（双账本交叉校验；0=两账本一致）。
 * <p>行入列条件：diff≠0 或 crossDiff≠0。
 */
public record GroupReconcileRowVO(Long groupId,
                                  String groupName,
                                  BigDecimal netAllocated,
                                  BigDecimal consumed,
                                  BigDecimal refunded,
                                  BigDecimal expected,
                                  BigDecimal balance,
                                  BigDecimal diff,
                                  BigDecimal crossDiff) {
}
