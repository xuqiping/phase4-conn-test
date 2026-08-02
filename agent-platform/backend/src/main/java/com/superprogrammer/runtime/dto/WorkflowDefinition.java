package com.superprogrammer.runtime.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDefinition {

    private String version;
    private Long workflowId;
    private String name;
    private List<RuntimeNode> nodes;
    private List<RuntimeEdge> edges;
}
