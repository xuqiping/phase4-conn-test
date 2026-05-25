// agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowDetailVO.java
package com.superprogrammer.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDetailVO {

    private Long id;
    private String name;
    private String description;
    private String status;
    private Long ownerId;
    private List<WorkflowNodeDTO> nodes;
    private List<WorkflowEdgeDTO> edges;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
