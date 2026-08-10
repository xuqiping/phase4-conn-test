package com.superprogrammer.asset.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/** 公众池审批申请视图，不包含项目内容。 */
@Data
@Builder
public class PublicAccessRequestVO {
    private Long id;
    private Long projectId;
    private Long applicantId;
    private String applicantUsername;
    private String status;
    private Long decidedBy;
    private OffsetDateTime decidedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
