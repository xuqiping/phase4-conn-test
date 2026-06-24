package com.superprogrammer.agent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SkillSaveRequest {

    private String name;

    private String description;

    private String type;

    private String config;

    private Integer sortOrder;

    private List<SkillStepSaveRequest> steps = new ArrayList<>();

    @Data
    public static class SkillStepSaveRequest {

        private Integer stepOrder;

        private String name;

        private String action;

        private String config;
    }
}
