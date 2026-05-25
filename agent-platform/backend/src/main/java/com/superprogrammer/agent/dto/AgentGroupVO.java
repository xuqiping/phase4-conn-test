// agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/AgentGroupVO.java
package com.superprogrammer.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentGroupVO {

    private Long id;
    private String name;
    private String icon;
    private String description;
    private Integer sortOrder;
    private Long agentCount;
    private LocalDateTime createdAt;
}
