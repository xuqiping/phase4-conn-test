// agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/Skill.java
package com.superprogrammer.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skills")
public class Skill extends BaseEntity {

    private Long agentId;

    private String name;

    private String description;

    private String type;

    private String config;

    private Integer sortOrder;
}
