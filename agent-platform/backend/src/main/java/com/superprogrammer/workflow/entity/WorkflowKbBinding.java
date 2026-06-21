package com.superprogrammer.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Workflow ↔ KnowledgeBase 检索范围绑定（V25，mirror agent_kb_bindings）。
 * 工作流级 scope；per-node kbIds 留后续（RETRIEVAL 节点自身 config 携 kbId）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_kb_bindings")
public class WorkflowKbBinding extends BaseEntity {

    private Long workflowId;

    private Long kbId;

    private Long tenantId;

    private Long grantedBy;
}
