package com.superprogrammer.billing.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 支付渠道网页配置（payment_channel_config，V143）。
 * <p>密钥整体 AES 加密存 {@link #configEncrypted}；{@link #configTails} 为脱敏回显 JSON，
 * 读取端点只出 tails，永不解密明文出库。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("payment_channel_config")
public class PaymentChannelConfigEntity extends BaseEntity {

    /** 渠道码：ALIPAY/WECHAT（DB CHECK 白名单）。 */
    private String channel;

    /** AES 加密后的配置 JSON 串。 */
    private String configEncrypted;

    /** 脱敏回显 JSON（{"appId":"****3f2a"}）。 */
    private String configTails;
}
