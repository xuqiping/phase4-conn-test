// agent-platform/backend/src/main/java/com/superprogrammer/execution/entity/ExecutionLog.java
package com.superprogrammer.execution.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "execution_logs", autoResultMap = true)
public class ExecutionLog extends BaseEntity {

    private Long workflowId;

    private String workflowName;

    private Long parentExecutionId;

    private Long rootExecutionId;

    private String sourceType;

    private Long sourceId;

    /** 触发该执行的聊天会话（从对话流触发工作流时填充，用于人机输入拦截定位）。 */
    private Long sessionId;

    private String nodeId;

    private String externalThreadId;

    private String checkpointRef;

    private String traceId;

    private Long triggeredBy;

    @TableField(exist = false)
    private String triggeredByUsername;

    private String status;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String variables;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String nodeLogs;

    /** WAITING_INPUT 期间缓存的待答问题规格（inputKey/question/inputType/options 等），恢复后清空。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String pendingInput;

    private OffsetDateTime startedAt;

    private OffsetDateTime completedAt;

    private Long duration;

    private String errorMessage;
}
