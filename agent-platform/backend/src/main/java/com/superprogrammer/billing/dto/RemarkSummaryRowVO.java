package com.superprogrammer.billing.dto;

import java.math.BigDecimal;

/**
 * 按备注汇总行（修复IV E1 · 12x-1，决策 4：独立汇总视图）：同一组织备注（users.remark）一桶。
 * <p>口径：余额/充值=全量累计（与用户余额视图同源）；消耗积分/调用次数=查询窗内
 * （from/to 走 {@code BillingQueryService.clamp} 默认近 30 天、上限 365 天）。
 * <p>remark=null 与 '' 同桶（SQL {@code COALESCE(u.remark,'')}），前端空值统一显「未填备注」。
 * LIMIT 1000 兜底（单租户量级全显，超出截断不静默——service 层注释声明）。
 */
public record RemarkSummaryRowVO(String remark,
                                 Long userCount,
                                 BigDecimal balanceSum,
                                 BigDecimal rechargePointsSum,
                                 BigDecimal rechargeAmountSum,
                                 BigDecimal consumePointsSum,
                                 Long callCount) {
}
