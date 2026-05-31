// agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/Permission.java
package com.superprogrammer.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("permissions")
public class Permission extends BaseEntity {

    private String name;

    private String code;

    private String resource;

    private String action;
}
