package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * C7 库级摘要 L-KB（规格 §9.1，V172）。版本化：重生成插新行 version+1，旧版留档。
 * status：READY=可用 / ERROR=连续失败待手动（summary 空，触发判定跳过 ERROR 行沿用上一 READY 版）。
 * 泄露面：summary/topics 仅 Service 内部读取注入 prompt，任何 API 不下发（规格 §9.3）。
 * 独立实体（不继承 BaseEntity——本表无 deleted 列且 version 是业务版本非乐观锁，继承会带入
 * @TableLogic deleted 使 MP 查询拼 deleted=0 直接 SQL 报错）。
 */
@Data
@TableName(value = "knowledge_base_summaries", autoResultMap = true)
public class KnowledgeBaseSummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long kbId;

    /** 业务版本（库内自 1 递增，非乐观锁）。 */
    private Integer version;

    private String status;

    /** 库级摘要 ≤2000 字；ERROR 行 NULL。 */
    private String summary;

    /** 主题清单 JSON 数组字符串，如 ["差旅","报销"]。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String topics;

    /** 生成统计 {"docCount":n,"batchCount":n,"model":"...","attempt":n}。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String stats;

    private OffsetDateTime generatedAt;

    private Long createdBy;

    private OffsetDateTime createdAt;

    private Long updatedBy;

    private OffsetDateTime updatedAt;
}
