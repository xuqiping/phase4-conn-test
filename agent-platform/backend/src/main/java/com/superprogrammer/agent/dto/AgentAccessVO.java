package com.superprogrammer.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentAccessVO {

    private Long agentId;

    private Boolean canManage;

    private Boolean canUse;

    private Boolean canReadPrompt;

    private Boolean canCopy;
}
