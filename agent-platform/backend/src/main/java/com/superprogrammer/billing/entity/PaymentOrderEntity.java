package com.superprogrammer.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 积分计费·充值订单（payment_order，V65）。
 * <p>MVP 仅 admin grant（channel=ADMIN，status 直 PAID）；Phase2 自助支付（ALIPAY/WECHAT）复用本表 + 回调状态机。
 * <p>不继承 BaseEntity：append-only（同 MediaGenTask 先例）。
 */
@Data
@TableName("payment_order")
public class PaymentOrderEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    public static final String CHANNEL_ADMIN = "ADMIN";
    public static final String CHANNEL_ALIPAY = "ALIPAY";
    public static final String CHANNEL_WECHAT = "WECHAT";

    @TableId(type = IdType.AUTO)
    private Long id;

    private OffsetDateTime createdAt;

    private Long userId;

    private BigDecimal amountYuan;

    private BigDecimal pointsGranted;

    private String status;

    private String channel;

    private String channelOrderId;

    private OffsetDateTime paidAt;
}
