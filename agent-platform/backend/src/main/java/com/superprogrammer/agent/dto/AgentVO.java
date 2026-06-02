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
public class AgentVO {

    private Long id;
    private String name;
    private String description;
    private String avatar;
    private String status;
    private Long groupId;
    private String groupName;
    private Long parentId;
    private String parentName;
    private Integer skillCount;
    private Integer subAgentCount;
    private Boolean isLeaf;
    private OffsetDateTime createdAt;
}
