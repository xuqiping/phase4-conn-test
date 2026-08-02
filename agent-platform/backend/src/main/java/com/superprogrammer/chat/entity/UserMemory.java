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
    private String memoryKeyZh;   // memory_key 中文标签（V32，如"女儿"）：前端「名称」列显示 + 关键词召回锚点
    private String memoryValue;
    private String source;
    private BigDecimal confidence;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    /** 总记忆可见性（V33，默认 true=老行=今天行为）。false 时经 user_memory_projects 挂项目。
     *  读可见性标签——决定"读时哪些 scope 拉它"，与 home 正交。 */
    private Boolean isGlobal;
    /** 写归属/唯一性槽（V34）：NULL=global home，否则=该 project home。
     *  决定"(user, home) 内 key 唯一"，与可见性(is_global+user_memory_projects)正交。
     *  每项目独立 key 靠不同 home 共存；跨项目共享靠可见性标签。 */
    private Long homeProjectId;
    // 记忆冲突解决（V27）
    private String blockLabel;     // 信息块（embed 聚类）
    private Long conflictId;       // 指向 memory_conflicts，null=干净
    // 实体标签 JSON 字符串（V31，如 ["女儿","北京"]）。VECTOR_KEYWORD 关键词召回用，写时 LLM 抽。
    // 列为 JSONB，自定义 SQL 走 ::jsonb；空/null = 不参与关键词召回。
    private String entities;
    // embedding halfvec(2048) 列不映射为字段——halfvec 走自定义 SQL（同 KnowledgeEmbedding 模式）
}
