package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/** 一次知识检索的顶层运行记录。 */
@Data
@TableName(value = "rag_retrieval_runs", autoResultMap = true)
public class RagRetrievalRun {
    @TableId(type = IdType.INPUT)
    private String id;
    private String traceId;
    private Long tenantId;
    private Long userId;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String kbIds;
    private String queryHash;
    private String queryType;
    private Long pipelineVersionId;
    private String knowledgeSnapshot;
    private String status;
    private String resultState;
    private Long latencyMs;
    private String errorCode;
    private String errorSummary;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
}
