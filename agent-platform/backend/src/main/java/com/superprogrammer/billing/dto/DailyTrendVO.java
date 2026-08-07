package com.superprogrammer.billing.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * admin 账单趋势（按日聚合，{@code GET /api/billing/admin/trend}）。
 * <p>{@code day} = 'YYYY-MM-DD'（按服务器时区 DATE_trunc('day', created_at)）。
 */
@Data
public class DailyTrendVO {
    private String day;
    private BigDecimal costYuan;
    private BigDecimal points;
    private Long callCount;
}
