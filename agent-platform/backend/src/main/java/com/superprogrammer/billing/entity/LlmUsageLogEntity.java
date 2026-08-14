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
    /** kind：知识库专用重排。 */
    public static final String KIND_RERANK = "RERANK";
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

    /** CHAT/EMBED/RERANK/IMAGE/VIDEO。 */
    private String kind;

    private Integer tokensInput;

    private Integer tokensOutput;

    /** 当时算的真实金额（价表改不动历史）。 */
    private BigDecimal costYuan;

    /** 当时折算积分（与 ledger 互证）。 */
    private BigDecimal pointsConsumed;

    private String status;

    private String errorMsg;

    /**
     * 请求 traceId（V95），与 {@code audit_logs.trace_id} 同值。
     * <p>chat 路径关联键：同请求「send_message → chat_completed → 本表」三处 traceId 一致。
     * media worker 无 MDC → null（媒体改用 {@link #taskId} 关联，坑点 #10）。
     */
    private String traceId;

    /**
     * 媒体任务 id（V95），与 media 审计行 {@code targetId} 对齐（{@code chargeMedia} 的 refId）。
     * <p>媒体路径关联键：审计两行（submit + success）{@code targetId=taskId} → 与本表 {@code task_id} 对齐。
     * chat/embed 无任务 → null。
     */
    private Long taskId;

    /**
     * 归属 chat 会话 id（安全体系 S3 · SEC-FR-056 / V122）：会话 token 上限 SUM 统计列。
     * null=非会话调用（记忆后台/文档解析/画布节点等）。
     */
    private String sessionId;
}
