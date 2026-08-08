package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 项目授权链视图（记忆二期 P2 · FR-101/102）。
 * 带双方项目名 + 发起人/审批人名（XML join），前端「我授权出去的 / 待我审批的」两栏直接用。
 */
@Data
@Builder
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
}
