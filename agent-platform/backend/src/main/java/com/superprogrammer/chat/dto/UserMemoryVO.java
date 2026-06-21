package com.superprogrammer.chat.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 用户长期记忆视图（自服务查询/管理）。 */
@Data
public class UserMemoryVO {

    private Long id;

    /** PREFERENCE / FACT / FEEDBACK */
    private String category;

    private String memoryKey;
    private String memoryValue;

    /** INFERRED（LLM 抽取）/ EXPLICIT（预留） */
    private String source;

    /** 0-1，注入阈值 ≥0.5 */
    private BigDecimal confidence;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // 记忆冲突解决（V27）
    private Long conflictId;
    private String conflictStatus;   // null / FLAGGED
    private String conflictWith;     // counterpart 摘要（如 "女儿小红"），无冲突 null
}
