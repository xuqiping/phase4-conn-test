package com.superprogrammer.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 项目↔个人授权视图（记忆二期 P1）。
 * <p>
 * 带项目名 + 被授权人名 + 发起/审批人名（XML join），前端「我授权出去的（项目→个人）/ 我被授权的 /
 * 待我审批的申请（个人→项目）」三栏直接用。
 * <p>
 * <b>构造器</b>：{@code @NoArgsConstructor @AllArgsConstructor} 显式补齐——单 {@code @Builder} 只生成全参构造器，
 * MyBatis 列序自动映射（applyColumnOrderBasedConstructorAutomapping）在结果列数 &lt; 构造器参数数时会 OOB
 * （同 {@link MemoryProjectEntryVO} 的 P4 坑）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryProjectUserGrantVO {

    private Long id;
    private Long projectId;
    private String projectName;
    private Long userId;
    private String userName;
    private String initiatedBy;      // PROJECT / USER
    private Long grantedBy;
    private String grantedByName;
    private Long approvedBy;
    private String approvedByName;
    private String status;           // PENDING / ACTIVE / REJECTED / REVOKED
    private OffsetDateTime createdAt;
    private OffsetDateTime approvedAt;
}
