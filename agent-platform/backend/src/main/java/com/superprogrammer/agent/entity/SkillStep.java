// agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/SkillStep.java
package com.superprogrammer.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill_steps")
public class SkillStep extends BaseEntity {

    private Long skillId;

    private Integer stepOrder;

    private String name;

    private String action;

    private String config;
}
