package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 检索或重排的显式降级事件。 */
@Data
@TableName("rag_fallback_events")
public class RagFallbackEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String retrievalRunId;
    private String rankingRunId;
    private String stage;
    private String configuredMode;
    private String effectiveMode;
    private String reasonCode;
    private String reasonSummary;
    private OffsetDateTime createdAt;
}
