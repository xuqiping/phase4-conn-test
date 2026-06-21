package com.superprogrammer.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentPermissionVO {

    private Long id;

    private Long agentId;

    private Long userId;

    private String username;

    private Boolean canUse;

    private Boolean canReadPrompt;

    private Boolean canCopy;

    private Long grantedBy;

    private OffsetDateTime updatedAt;
}
