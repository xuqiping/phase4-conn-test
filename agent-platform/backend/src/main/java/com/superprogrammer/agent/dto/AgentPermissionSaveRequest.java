package com.superprogrammer.agent.dto;

import lombok.Data;

@Data
public class AgentPermissionSaveRequest {

    private Long userId;

    private Boolean canUse;

    private Boolean canReadPrompt;

    private Boolean canCopy;
}
