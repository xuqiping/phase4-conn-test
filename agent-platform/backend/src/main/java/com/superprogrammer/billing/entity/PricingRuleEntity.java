package com.superprogrammer.billing.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
@TableName(value = "pricing_rule", autoResultMap = true)
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

    /** ALWAYS：清空（null）也落库——模式/类型切换后不留对面字段残值（下同）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal priceInputPerMillion;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal priceOutputPerMillion;

    /** 视频：TOKEN|SECOND。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String videoBillingMode;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal pricePerSecond;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal pricePerImage;

    /**
     * 7x-3（V95）：仅 VIDEO 有意义。TRUE=带参考视频任务的价；FALSE=无参考（或作为兜底）。
     * CHAT/EMBED/IMAGE 始终 FALSE。NOT NULL DEFAULT FALSE 保证旧行兼容。
     */
    private Boolean hasReference;

    /**
     * 7x-1（V152）：仅 VIDEO SECOND 行有意义——分辨率定价维度（480p/720p/768p/1080p/2k/4K）；
     * NULL=通用行（未单列分辨率的任务回落此行，与 has_reference=false 兜底同范式）。
     * CHAT/EMBED/RERANK/IMAGE/VIDEO TOKEN 行恒 NULL。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String resolution;

    /**
     * 7x-2（V152→V153）：仅 VIDEO TOKEN 模式有意义——提交期预估秒价，JSONB 一行多分辨率参数：
     * {@code {"general":0.1,"720p":0.2,"1080p":0.3}}，键 ⊆ general/480p/720p/768p/1080p/2k/4k，
     * general=未单列分辨率的兜底。TOKEN 提交期无 token 维度，估价预检用「任务分辨率对应值×时长」；
     * 真实扣费仍按 Ark 返的 total_tokens，本字段不参与计费。
     */
    @TableField(typeHandler = com.superprogrammer.common.typehandler.JsonbStringTypeHandler.class,
            updateStrategy = FieldStrategy.ALWAYS)
    private String estPerResolution;

    /**
     * 视频TOKEN分辨率分档计价（V162）：仅 VIDEO+TOKEN 行有意义——每百万价按分辨率分档，
     * JSONB 一行多档：{@code {"480p":6.5,"720p":12.3,"1080p":27.8,"4k":111.2}}，值=¥/百万 token。
     * 键 ⊆ 480p/720p/768p/1080p/2k/4k（无 general——通用/兜底价=priceInputPerMillion 列复用）；未配档位回落通用价。
     * 键归一 trim+小写（4K→4k，配置存/结算取同用 PricingService.normalizeResolution）。
     * SECOND/CHAT/EMBED/RERANK/IMAGE 行恒 NULL；不参与估价（estPerResolution 独立两套）。
     */
    @TableField(typeHandler = com.superprogrammer.common.typehandler.JsonbStringTypeHandler.class,
            updateStrategy = FieldStrategy.ALWAYS)
    private String tokenPricePerResolution;

    // ==================== 人工测试遗留问题修复II D（V160）：闲时价 + 缓存价 ====================
    // 全 NULL 默认 = 存量计费逐分不变：off_peak_*=NULL → 取忙时列；price_cached=NULL → 缓存价=输入价。

    /** 闲时输入价 ¥/1M；NULL=同忙时（priceInputPerMillion）。仅 CHAT/EMBED/RERANK。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal offPeakInputPerMillion;

    /** 闲时输出价 ¥/1M；NULL=同忙时。仅 CHAT。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal offPeakOutputPerMillion;

    /** 闲时缓存命中价 ¥/1M；NULL=回落 priceCachedPerMillion→输入价链。仅 CHAT。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal offPeakCachedPerMillion;

    /** 缓存命中读 token 价 ¥/1M；NULL=同输入价（缓存不省钱则不单配）。仅 CHAT。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal priceCachedPerMillion;

    private OffsetDateTime effectiveFrom;
}
