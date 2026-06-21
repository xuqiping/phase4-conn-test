package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记忆冲突（V27）。表无 deleted/version → 非 BaseEntity。
 * 特型列 new_memory(JSONB)/new_embedding(halfvec)/existing_memory_ids(BIGINT[])
 * 写入走 MemoryConflictMapper 自定义 @Insert（casts），读取复杂列走 mapper 自定义 @Select/VO。
 */
@Data
@TableName("memory_conflicts")
public class MemoryConflict {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long sessionId;
    private String blockLabel;
    private String newMemory;              // JSONB 文本
    private String newEmbedding;           // halfvec 文本 '[..]'
    private List<Long> existingMemoryIds;  // BIGINT[]
    private String askText;
    private String status;                 // PENDING/FLAGGED/RESOLVED
    private String resolution;             // KEEP_NEW/KEEP_OLD/KEEP_BOTH/DISCARD/FLAGGED
    private OffsetDateTime createdAt;
    private OffsetDateTime resolvedAt;
    private OffsetDateTime expiresAt;
}
