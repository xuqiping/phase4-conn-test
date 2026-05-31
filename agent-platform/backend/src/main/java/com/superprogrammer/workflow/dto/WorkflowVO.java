// agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowVO.java
package com.superprogrammer.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowVO {

    private Long id;
    private String name;
    private String description;
    private String status;
    private Long ownerId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
