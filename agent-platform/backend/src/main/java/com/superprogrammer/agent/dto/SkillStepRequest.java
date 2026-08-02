package com.superprogrammer.agent.dto;

import lombok.Data;

@Data
public class SkillStepRequest {
    private Integer stepOrder;
    private String name;
    private String action;
    private String config;
}
