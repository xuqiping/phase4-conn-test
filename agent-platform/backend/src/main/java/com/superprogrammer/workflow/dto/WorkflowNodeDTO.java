// agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowNodeDTO.java
package com.superprogrammer.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowNodeDTO {

    private Long id;
    private String nodeId;
    private String type;
    private Double positionX;
    private Double positionY;
    private String label;
    private String config;
}
