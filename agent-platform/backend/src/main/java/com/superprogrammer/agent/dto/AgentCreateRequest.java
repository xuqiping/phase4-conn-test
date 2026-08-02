package com.superprogrammer.agent.dto;

import lombok.Data;

@Data
public class AgentCreateRequest {
    private String name;
    private String description;
    private String avatar;
    private Long groupId;
    private Long parentId;
}
