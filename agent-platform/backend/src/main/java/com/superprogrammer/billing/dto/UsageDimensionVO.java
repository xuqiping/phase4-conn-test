package com.superprogrammer.billing.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * admin 排行维度行（by-user / by-model / by-kind 通用）。
 * <p>{@code dimensionKey}：by-user=用户 id(text)、by-model=模型名、by-kind=CHAT/EMBED/IMAGE/VIDEO。
 * 前端 by-user 想显用户名时另起批量用户查询（避免聚合 SQL 做 N+1 join）。
 */
@Data
public class UsageDimensionVO {
    private String dimensionKey;
    private Long tokensInput;
    private Long tokensOutput;
    private BigDecimal costYuan;
    private BigDecimal points;
    private Long callCount;
}
