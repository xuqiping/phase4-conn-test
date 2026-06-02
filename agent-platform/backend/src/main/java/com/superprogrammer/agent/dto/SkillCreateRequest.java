package com.superprogrammer.agent.dto;

import lombok.Data;

@Data
public class SkillCreateRequest {
    private Long agentId;
    private String name;
    private String description;
    private String type;
    private Integer sortOrder;
}
