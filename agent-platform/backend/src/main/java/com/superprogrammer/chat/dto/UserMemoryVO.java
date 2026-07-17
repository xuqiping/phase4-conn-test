package com.superprogrammer.chat.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** 用户长期记忆视图（自服务查询/管理）。 */
@Data
public class UserMemoryVO {

    private Long id;

    /** PREFERENCE / FACT / FEEDBACK */
    private String category;

    private String memoryKey;
    private String memoryKeyZh;   // memory_key 中文标签（V32，如"女儿"）：前端「名称」列显示
    private String memoryValue;
    private String blockLabel;    // 信息块标签（embed 聚类，如"家庭/个人信息"）：前端「信息块」列显示 + 关键词召回锚点

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

    // 项目记忆 scope（V33）
    private Boolean isGlobal;        // 是否总记忆可见，true=总记忆，false=仅项目
    private List<Long> projectIds;   // 挂载的项目 id（空=is_global 记忆）
    /** 写归属 home（V34，NULL=总记忆 home）。M1 归属列区分「归属」(home) vs「共享」(可见性 projectIds)。 */
    private Long homeProjectId;
}
