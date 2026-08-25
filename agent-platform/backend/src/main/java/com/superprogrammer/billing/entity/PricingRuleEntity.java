package com.superprogrammer.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 积分计费·模型价表（pricing_rule，V66）。
 * <p>多形态（kind=CHAT/EMBED/IMAGE/VIDEO）。provider_id 可空=全局价，非空=provider 专属价（优先）。
 * <p>effective_from 生效起点：改价写新行，旧流水不动；询价取 &lt;=now 最新。
 * <p>不继承 BaseEntity：配置行 append（同 MediaGenTask 先例）。
 */
@Data
@TableName("pricing_rule")
public class PricingRuleEntity {

    public static final String KIND_CHAT = "CHAT";
    public static final String KIND_EMBED = "EMBED";
    public static final String KIND_RERANK = "RERANK";
    public static final String KIND_IMAGE = "IMAGE";
    public static final String KIND_VIDEO = "VIDEO";

    public static final String VIDEO_MODE_TOKEN = "TOKEN";
    public static final String VIDEO_MODE_SECOND = "SECOND";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String kind;

    /** null=全局价；非空=provider 专属价（命中优先于全局）。 */
    private Long providerId;

    private String model;

    private BigDecimal priceInputPerMillion;

    private BigDecimal priceOutputPerMillion;

    /** 视频：TOKEN|SECOND。 */
    private String videoBillingMode;

    private BigDecimal pricePerSecond;

    private BigDecimal pricePerImage;

    /**
     * 7x-3（V95）：仅 VIDEO 有意义。TRUE=带参考视频任务的价；FALSE=无参考（或作为兜底）。
     * CHAT/EMBED/IMAGE 始终 FALSE。NOT NULL DEFAULT FALSE 保证旧行兼容。
     */
    private Boolean hasReference;

    /**
     * 7x-1（V152）：仅 VIDEO SECOND 行有意义——分辨率定价维度（480p/720p/1080p/4K）；
     * NULL=通用行（未单列分辨率的任务回落此行，与 has_reference=false 兜底同范式）。
     * CHAT/EMBED/RERANK/IMAGE/VIDEO TOKEN 行恒 NULL。
     */
    private String resolution;

    /**
     * 7x-2（V152）：仅 VIDEO TOKEN 模式有意义——提交期预估秒价（¥/秒）。
     * TOKEN 模式提交期无 token 维度，估价预检（余额≥预估才放行）用本字段×时长估算；
     * 真实扣费仍按 Ark 返的 total_tokens，本字段不参与计费。
     */
    private java.math.BigDecimal estYuanPerSecond;

    private OffsetDateTime effectiveFrom;
}
