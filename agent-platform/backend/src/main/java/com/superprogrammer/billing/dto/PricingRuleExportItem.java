package com.superprogrammer.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 价表导出/导入条目（7x-2）。
 * <p>导出：把 pricing_rule 全量行映射到此 DTO（价表无加密，纯字段拷贝）。
 * 导入：按 (providerId, model, kind, hasReference) upsert——存在则覆盖价格并刷新 effective_from，
 * 不存在则插入。{@code providerName} 仅模板填充与可读性用，导入时忽略（按 providerId 定位）。
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown=true)}：导入旧文件/异构文件时忽略未知字段，防注入失败。
 * <p>与 {@link LlmProviderExportItem} 镜像，但无明文密钥概念。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PricingRuleExportItem {
    /** CHAT / EMBED / IMAGE / VIDEO */
    private String kind;
    /** 全局供应商 DB id（导入 upsert 的匹配键之一，必填） */
    private Long providerId;
    /** 仅模板/导出可读性用，导入时忽略（按 providerId 定位） */
    private String providerName;
    /** 模型名（必填，upsert 匹配键之一） */
    private String model;
    /** 7x-3：仅 VIDEO 有意义；true=带参考视频价，false=无参考/兜底。null 视为 false */
    private Boolean hasReference;
    /** 7x-1（V152）：仅 VIDEO SECOND 有意义（480p/720p/768p/1080p/2k/4k；null=通用行），upsert 匹配键之一 */
    private String resolution;
    /** 7x-2（V153）：仅 VIDEO TOKEN 有意义——提交期预估秒价（general/分辨率 → ¥/秒，仅预检） */
    private java.util.Map<String, BigDecimal> estPerResolution;
    /** V162：TOKEN 每百万价分辨率档（480p/720p/768p/1080p/2k/4k → ¥/百万，仅 VIDEO TOKEN）。导入三态：字段缺失/null=不动库中现有档，{}=清空，非空=整体覆盖 */
    private java.util.Map<String, BigDecimal> tokenPricePerResolution;
    /** V164（MVR-3）：SECOND 秒价分辨率档（480p/720p/768p/1080p/2k/4k → ¥/秒，仅 VIDEO SECOND）。导入三态：字段缺失/null=不动库中现有档，{}=清空，非空=整体覆盖 */
    private java.util.Map<String, BigDecimal> pricePerSecondPerResolution;
    /** 文本/embed 每 1M input token 价（¥） */
    private BigDecimal priceInputPerMillion;
    /** 文本 每 1M output token 价（¥） */
    private BigDecimal priceOutputPerMillion;
    /** 视频：TOKEN | SECOND */
    private String videoBillingMode;
    /** 视频 SECOND：每秒价（¥） */
    private BigDecimal pricePerSecond;
    /** 图片：每张价（¥） */
    private BigDecimal pricePerImage;
    /** D（V160）闲时/缓存四新列；仅 CHAT/EMBED/RERANK 行有意义，其他恒 null（NULL=回落语义） */
    private BigDecimal offPeakInputPerMillion;
    private BigDecimal offPeakOutputPerMillion;
    private BigDecimal offPeakCachedPerMillion;
    private BigDecimal priceCachedPerMillion;
}
