// agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowCreateRequest.java
package com.superprogrammer.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowCreateRequest {

    @NotBlank(message = "工作流名称不能为空")
    private String name;

    private String description;

    private List<WorkflowNodeDTO> nodes;

    private List<WorkflowEdgeDTO> edges;
}
