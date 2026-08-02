package com.superprogrammer.workflow.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Workflow ↔ KB 绑定视图（阶段5 检索 scope 管理）。
 */
@Data
@Builder
public class WorkflowKbBindingVO {

    private Long kbId;
    private String kbName;
}
