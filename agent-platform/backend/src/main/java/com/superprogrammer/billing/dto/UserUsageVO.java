package com.superprogrammer.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 用户积分消耗明细行（{@code GET /api/billing/me/usage}）。
 * <p>用户侧不暴露 token/¥：仅显时间 + 模型 + 类型(CHAT/EMBED/IMAGE/VIDEO) + 消耗积分 + 状态。
 * ownership 由 service 强制按 current userId 过滤。
 */
@Data
public class UserUsageVO {
    private OffsetDateTime createdAt;
    private String model;
    private String kind;
    private BigDecimal pointsConsumed;
    private String status;
}
