// agent-platform/backend/src/main/java/com/superprogrammer/workflow/entity/WorkflowNode.java
package com.superprogrammer.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_nodes")
public class WorkflowNode extends BaseEntity {

    private Long workflowId;

    private String nodeId;

    private String type;

    private Double positionX;

    private Double positionY;

    private String label;

    private String config;
}
