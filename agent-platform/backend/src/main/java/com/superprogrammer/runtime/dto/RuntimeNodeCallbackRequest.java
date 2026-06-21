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
public class RuntimeNodeCallbackRequest {

    private String executionId;
    private String rootExecutionId;
    private String parentExecutionId;
    private String nodeId;
    private String sourceType;
    private Long sourceId;
    private Long userId;
    private Map<String, Object> input;
    private Map<String, Object> metadata;
    private String traceId;
}
