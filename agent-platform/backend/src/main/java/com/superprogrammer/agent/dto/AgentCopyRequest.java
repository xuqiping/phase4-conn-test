package com.superprogrammer.agent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentCopyRequest {

    private String name;

    private String description;

    private String avatar;

    private Long groupId;

    private String config;

    private List<SkillSaveRequest> skills = new ArrayList<>();
}
