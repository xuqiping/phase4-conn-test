// agent-platform/backend/src/main/java/com/superprogrammer/workflow/entity/WorkflowEdge.java
package com.superprogrammer.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_edges")
public class WorkflowEdge extends BaseEntity {

    private Long workflowId;

    private String sourceNodeId;

    private String targetNodeId;

    private String sourceHandle;

    private String targetHandle;

    private String label;

    private String condition;
}
