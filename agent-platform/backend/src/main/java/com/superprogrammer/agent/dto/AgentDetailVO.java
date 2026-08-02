package com.superprogrammer.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
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
    private Long parentId;
    private String parentName;
    private Boolean isLeaf;
    private List<AgentVO> subAgents;
    private List<SkillVO> skills;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
