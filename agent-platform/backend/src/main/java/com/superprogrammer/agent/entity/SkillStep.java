// agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/SkillStep.java
package com.superprogrammer.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "skill_steps", autoResultMap = true)
public class SkillStep extends BaseEntity {

    private Long skillId;

    private Integer stepOrder;

    private String name;

    private String action;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String config;
}
