// agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/SkillDetailVO.java
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
public class SkillDetailVO {

    private Long id;
    private Long agentId;
    private String agentName;
    private String name;
    private String description;
    private String type;
    private String config;
    private Integer sortOrder;
    private List<SkillStepVO> steps;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillStepVO {
        private Long id;
        private Integer stepOrder;
        private String name;
        private String action;
        private String config;
    }
}
