// agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/AgentGroup.java
package com.superprogrammer.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_groups")
public class AgentGroup extends BaseEntity {

    private String name;

    private String icon;

    private String description;

    private Integer sortOrder;
}
