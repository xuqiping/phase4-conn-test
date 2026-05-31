// agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/SkillVO.java
package com.superprogrammer.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillVO {

    private Long id;
    private String name;
    private String description;
    private String type;
    private Integer sortOrder;
    private OffsetDateTime createdAt;
}
