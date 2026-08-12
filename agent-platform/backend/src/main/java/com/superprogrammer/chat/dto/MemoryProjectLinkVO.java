package com.superprogrammer.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 项目授权链视图（记忆二期 P2 · FR-101/102）。
 * 带双方项目名 + 发起人/审批人名（XML join），前端「我授权出去的 / 待我审批的」两栏直接用。
 * <b>构造器</b>：{@code @NoArgsConstructor @AllArgsConstructor} 显式补齐——单 {@code @Builder} 只生成全参构造器，
 * MyBatis resultMap 无 no-arg ctor 时回退按 SELECT 列序位置映射，列序与字段序错位即 OOB（三期加 revoke 字段即触发）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryProjectLinkVO {

    private Long id;
    private Long parentProjectId;
    private String parentProjectName;
    private Long childProjectId;
    private String childProjectName;
    private Long grantedBy;
    private String grantedByName;
    private Long approvedBy;
    private String approvedByName;
    private String status;           // PENDING / ACTIVE / REJECTED / REVOKED
    private OffsetDateTime createdAt;
    private OffsetDateTime approvedAt;
    // 三期非对称撤销：非空=child owner 已申请撤销，待 parent 审批（status 仍 ACTIVE）
    private Long revokeRequestedBy;
    private String revokeRequestedByName;
    private OffsetDateTime revokeRequestedAt;
}
