// agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/Agent.java
package com.superprogrammer.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agents")
public class Agent extends BaseEntity {

    private String name;

    private String description;

    private String avatar;

    private Long groupId;

    private String status;

    private String config;

    private Long parentId;
}
