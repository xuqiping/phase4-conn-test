// agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/Skill.java
package com.superprogrammer.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "skills", autoResultMap = true)
public class Skill extends BaseEntity {

    private Long agentId;

    private String name;

    private String description;

    private String type;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String config;

    private Integer sortOrder;
}
