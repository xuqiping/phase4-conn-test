package com.superprogrammer.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 积分计费·LLM 调用审计日志（llm_usage_logs，V65）。
 * <p>admin 看真 token+¥+积分；用户侧不暴露 token/¥。
 * <p>不含 prompt/回答原文（仅 token 计数 + 元数据），无 PII。
 * <p>不继承 BaseEntity：append-only 异步攒批写（同 MediaGenTask 先例）。
 */
@Data
@TableName("llm_usage_logs")
public class LlmUsageLogEntity {

    /** kind：文本对话。 */
    public static final String KIND_CHAT = "CHAT";
    /** kind：向量嵌入。 */
    public static final String KIND_EMBED = "EMBED";
    /** kind：图片生成（stub，未接真 provider）。 */
    public static final String KIND_IMAGE = "IMAGE";
    /** kind：视频生成。 */
    public static final String KIND_VIDEO = "VIDEO";

    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_USER = "USER";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_ESTIMATED = "ESTIMATED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private OffsetDateTime createdAt;

    /** nullable：系统调用无 user（仍采不扣）。 */
    private Long userId;

    private Long providerId;

    /** GLOBAL/USER。 */
    private String providerScope;

    private String model;

    /** CHAT/EMBED/IMAGE/VIDEO。 */
    private String kind;

    private Integer tokensInput;

    private Integer tokensOutput;

    /** 当时算的真实金额（价表改不动历史）。 */
    private BigDecimal costYuan;

    /** 当时折算积分（与 ledger 互证）。 */
    private BigDecimal pointsConsumed;

    private String status;

    private String errorMsg;
}
