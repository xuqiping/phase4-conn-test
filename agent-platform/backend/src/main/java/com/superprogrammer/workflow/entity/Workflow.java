// agent-platform/backend/src/main/java/com/superprogrammer/workflow/entity/Workflow.java
package com.superprogrammer.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflows")
public class Workflow extends BaseEntity {

    private String name;

    private String description;

    private String status;

    private Long ownerId;

    /** 记忆模式开关（V26，null=继承 global）。 */
    private Boolean ragEnabled;
}
