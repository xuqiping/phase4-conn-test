package com.superprogrammer.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent ↔ KnowledgeBase 检索范围绑定（V25，mirror agent_permissions）。
 * Agent 绑定的 KB = 检索 scope；P4 = 执行身份权限 ∩ 此绑定（RagScopeResolver）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_kb_bindings")
public class AgentKbBinding extends BaseEntity {

    private Long agentId;

    private Long kbId;

    private Long tenantId;

    private Long grantedBy;
}
