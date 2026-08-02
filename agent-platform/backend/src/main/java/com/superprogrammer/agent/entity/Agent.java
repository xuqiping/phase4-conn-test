// agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/Agent.java
package com.superprogrammer.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agents", autoResultMap = true)
public class Agent extends BaseEntity {

    private String name;

    private String description;

    private String avatar;

    private Long groupId;

    private String status;

    /** Agent 配置（jsonb）：含 ragEnabled 三态（null=继承）、人设等。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String config;

    private Long parentId;
}
