package com.superprogrammer.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * admin 调用明细行（{@code /admin/call-log} 逐条 llm_usage_logs 记录）。
 * <p>含真 token/¥/积分（admin 侧，区别于用户侧 {@link UserUsageVO} 刻意省略 token/¥）。
 * <p>{@code username}/{@code displayName} 由 SQL LEFT JOIN users 取（user_id 可空=系统调用，此时两字段为 null）。
 */
@Data
public class UsageDetailVO {
    private Long id;
    private OffsetDateTime createdAt;
    private Long userId;
    /** 登录名（users.username）。系统调用(user_id=null)时为 null。 */
    private String username;
    /** 显示名（users.name），可空；前端为空时回退 username。 */
    private String displayName;
    private String model;
    private String kind;
    private Integer tokensInput;
    private Integer tokensOutput;
    private BigDecimal costYuan;
    private BigDecimal pointsConsumed;
    private String status;
    private String errorMsg;
    /** 8x Chunk7：请求 traceId（chat 路径关联键，与 audit_logs.trace_id 同值）。 */
    private String traceId;
    /** 8x Chunk7：媒体任务 id（媒体路径关联键，与 media 审计行 targetId 对齐）。 */
    private Long taskId;
}
