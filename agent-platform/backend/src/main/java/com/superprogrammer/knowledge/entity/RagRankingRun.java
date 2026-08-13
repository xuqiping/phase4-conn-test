package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 一次 Ranking Engine 运行记录，多批模型调用可共享该记录。 */
@Data
@TableName("rag_ranking_runs")
public class RagRankingRun {
    @TableId(type = IdType.INPUT)
    private String id;
    private String retrievalRunId;
    private String configuredMode;
    private String effectiveMode;
    private Long modelConfigId;
    private String rankingConfigVersion;
    private Integer candidateCount;
    private Integer finalCount;
    private String candidateHash;
    private String fallbackReason;
    private String status;
    private Long latencyMs;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
}
