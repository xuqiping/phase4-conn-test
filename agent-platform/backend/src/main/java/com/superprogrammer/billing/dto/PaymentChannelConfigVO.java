package com.superprogrammer.billing.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 支付渠道配置·脱敏视图（admin 管理台回显用）。
 * <p><b>永不含明文</b>——tails 每字段形如 {@code "****3f2a"}。</p>
 */
@Data
@AllArgsConstructor
public class PaymentChannelConfigVO {

    /** 渠道码：ALIPAY/WECHAT。 */
    private String channel;

    /** 是否已配置齐必填键。 */
    private boolean configured;

    /** 各字段脱敏尾巴（{"appId":"****3f2a"}；未配置为空 map）。 */
    private Map<String, String> tails;

    private OffsetDateTime updatedAt;

    private Long updatedBy;
}
