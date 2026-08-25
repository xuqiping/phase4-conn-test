package com.superprogrammer.billing.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * admin 排行维度行（by-user / by-model / by-kind 通用）。
 * <p>{@code dimensionKey}：by-user=用户 id(text)、by-model=模型名、by-kind=CHAT/EMBED/IMAGE/VIDEO。
 * <p>D2（20x-1）：by-user 单 JOIN users 带 username/displayName（PK 单表 JOIN 非 N+1）；
 * by-model/by-kind 两路不查用户列，恒 null。
 */
@Data
public class UsageDimensionVO {
    private String dimensionKey;
    /** by-user 专属：登录账号（users.username） */
    private String username;
    /** by-user 专属：昵称/姓名（users.name，可空回退 username） */
    private String displayName;
    private Long tokensInput;
    private Long tokensOutput;
    private BigDecimal costYuan;
    private BigDecimal points;
    private Long callCount;
}
