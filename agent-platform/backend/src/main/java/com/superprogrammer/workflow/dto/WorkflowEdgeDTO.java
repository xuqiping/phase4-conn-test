// agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowEdgeDTO.java
package com.superprogrammer.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowEdgeDTO {

    private Long id;
    private String sourceNodeId;
    private String targetNodeId;
    private String sourceHandle;
    private String targetHandle;
    private String label;
    private String condition;
}
