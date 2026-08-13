package com.superprogrammer.knowledge.dto;

import com.superprogrammer.knowledge.entity.RagModelCall;
import com.superprogrammer.knowledge.entity.RagRankingRun;
import com.superprogrammer.knowledge.entity.RagRetrievalRun;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** 管理端 RAG Trace 聚合详情；只返回关联元数据和 Hash，不返回敏感正文。 */
@Data
public class RagTraceDetailVO {
    private String traceId;
    private List<RagRetrievalRun> retrievals;
    private List<RagRankingRun> rankings;
    private List<RagModelCall> modelCalls;
    private List<UsageItem> usages;
    private List<AuditItem> audits;

    @Data
    public static class UsageItem {
        private Long id;
        private OffsetDateTime createdAt;
        private Long userId;
        private String model;
        private String kind;
        private Integer tokensInput;
        private Integer tokensOutput;
        private BigDecimal costYuan;
        private BigDecimal pointsConsumed;
        private String status;
    }

    @Data
    public static class AuditItem {
        private Long id;
        private OffsetDateTime createdAt;
        private Long userId;
        private String username;
        private String module;
        private String action;
        private String targetType;
        private String targetId;
        private String result;
    }
}
