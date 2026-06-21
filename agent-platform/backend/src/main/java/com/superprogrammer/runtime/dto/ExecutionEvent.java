package com.superprogrammer.runtime.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionEvent {

    private String executionId;
    private String rootExecutionId;
    private String parentExecutionId;
    private String nodeId;
    private String type;
    private String status;
    private String sourceType;
    private Long sourceId;
    private Map<String, Object> input;
    private Map<String, Object> output;
    private Map<String, Object> metadata;
    private OffsetDateTime timestamp;
}
