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
    /** V140：用户取消/过期关闭（终态；CLOSED 后回调遇真实付款不走自动入账，进对账异常人工补单）。 */
    public static final String STATUS_CLOSED = "CLOSED";

    public static final String CHANNEL_ADMIN = "ADMIN";
    public static final String CHANNEL_ALIPAY = "ALIPAY";
    public static final String CHANNEL_WECHAT = "WECHAT";
    /** V140：mock 支付通道（billing.payment.mock-enabled=true 才可用；prod 开启启动即炸）。 */
    public static final String CHANNEL_MOCK = "MOCK";

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

    /** V140：渠道付款账号（充值记录六字段；日志掩码）。 */
    private String payerAccount;

    /** V140：PENDING 过期时间（过期 job 扫此列关单）。 */
    private OffsetDateTime expireAt;

    /** V140：前端幂等键（uk_payment_idem 防重复下单）。 */
    private String idemKey;
}
