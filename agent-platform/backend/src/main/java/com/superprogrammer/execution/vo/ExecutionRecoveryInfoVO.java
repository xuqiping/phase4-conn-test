package com.superprogrammer.execution.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExecutionRecoveryInfoVO {

    private Long executionId;

    private String status;

    private String failedNodeId;

    private String errorMessage;

    private String checkpointRef;

    private boolean recoverable;

    private String recoverySuggestion;
}
