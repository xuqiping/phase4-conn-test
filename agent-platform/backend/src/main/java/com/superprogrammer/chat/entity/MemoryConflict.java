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
    // V47 计划12：新模型冲突只来自总结时序互斥（无 type 列），关联 tag + 待裁决 summary。
    // 旧列（block_label/new_memory 等）H 收尾随旧表语义废弃。
    private Long tagId;                    // 冲突关联标签（时序互斥在同 tag 下）
    private Long summaryId;                // 冲突关联待裁决 summary
    private String resolution;             // KEEP_NEW/KEEP_OLD/KEEP_BOTH/DISCARD/FLAGGED
    private OffsetDateTime createdAt;
    private OffsetDateTime resolvedAt;
    private OffsetDateTime expiresAt;

    /** 二期 P4（FR-303）：true=项目共享总结冲突（裁决权=项目 ACTIVE owner/admin，非冲突行 user_id）。
     *  瞬态标记，非 DB 列——service 层 listPending 并集时打标供前端 badge。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean projectShared;
}
