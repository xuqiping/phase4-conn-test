package com.superprogrammer.billing.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * admin 账单总览（{@code GET /api/billing/admin/overview}）。
 * <p>聚合一段时间的总量：token 数（admin 可见真 token）+ 真实金额 ¥ + 积分 + 调用次数。
 */
@Data
public class UsageOverviewVO {
    /** 期内 input token 总和（含视频伪-token，按 kind 分列时区分）。 */
    private Long totalTokensInput;
    private Long totalTokensOutput;
    /** 期内真实金额 ¥ 总和（FAILED 行 cost 为 null，SUM 自动忽略）。 */
    private BigDecimal totalCostYuan;
    /** 期内消耗积分总和。 */
    private BigDecimal totalPoints;
    /** 期内调用次数（含 FAILED）。 */
    private Long callCount;
}
