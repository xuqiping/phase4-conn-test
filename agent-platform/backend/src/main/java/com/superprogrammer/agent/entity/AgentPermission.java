package com.superprogrammer.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_permissions")
public class AgentPermission extends BaseEntity {

    private Long agentId;

    private Long userId;

    private Boolean canUse;

    private Boolean canReadPrompt;

    private Boolean canCopy;

    private Long grantedBy;
}
