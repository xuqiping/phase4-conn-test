// agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/AgentDetailVO.java
package com.superprogrammer.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDetailVO {

    private Long id;
    private String name;
    private String description;
    private String avatar;
    private String status;
    private String config;
    private Long groupId;
    private String groupName;
    private List<SkillVO> skills;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
