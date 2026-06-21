package com.superprogrammer.runtime.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionRequest {

    private String executionId;
    private String rootExecutionId;
    private String parentExecutionId;
    private Long userId;
    private String sourceType;
    private Long sourceId;
    private WorkflowDefinition workflow;
    private Map<String, Object> input;
    private Map<String, Object> runtime;
}
