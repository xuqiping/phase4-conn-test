// agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/AgentVO.java
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
public class AgentVO {

    private Long id;
    private String name;
    private String description;
    private String avatar;
    private String status;
    private Long groupId;
    private String groupName;
    private Integer skillCount;
    private LocalDateTime createdAt;
}
