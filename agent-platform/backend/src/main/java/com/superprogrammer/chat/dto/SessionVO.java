package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class SessionVO {

    private Long id;
    private String title;
    private Long agentId;
    private String agentName;
    private Long workflowId;
    private String workflowName;
    private String mode;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
