package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@TableName("user_memories")
public class UserMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String category;
    private String memoryKey;
    private String memoryValue;
    private String source;
    private BigDecimal confidence;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    // 记忆冲突解决（V27）
    private String blockLabel;     // 信息块（embed 聚类）
    private Long conflictId;       // 指向 memory_conflicts，null=干净
    // embedding halfvec(2048) 列不映射为字段——halfvec 走自定义 SQL（同 KnowledgeEmbedding 模式）
}
