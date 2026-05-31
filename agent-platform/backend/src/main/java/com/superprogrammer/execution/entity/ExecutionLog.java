// agent-platform/backend/src/main/java/com/superprogrammer/execution/entity/ExecutionLog.java
package com.superprogrammer.execution.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("execution_logs")
public class ExecutionLog extends BaseEntity {

    private Long workflowId;

    private String workflowName;

    private Long triggeredBy;

    private String status;

    private String variables;

    private String nodeLogs;

    private OffsetDateTime startedAt;

    private OffsetDateTime completedAt;

    private Long duration;

    private String errorMessage;
}
