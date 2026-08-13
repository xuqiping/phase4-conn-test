package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** RAG 内部的一次模型调用摘要，不保存完整 Prompt 或 Chunk。 */
@Data
@TableName("rag_model_calls")
public class RagModelCall {
    @TableId(type = IdType.INPUT)
    private String id;
    private String traceId;
    private String retrievalRunId;
    private String rankingRunId;
    private String modelRequestId;
    private String providerRequestId;
    private String callPurpose;
    private Long modelConfigId;
    private String modelName;
    private String providerName;
    private String inputHash;
    private String outputHash;
    private Integer promptTokens;
    private Integer completionTokens;
    private BigDecimal costPoints;
    private String status;
    private Long latencyMs;
    private String errorSummary;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
}
