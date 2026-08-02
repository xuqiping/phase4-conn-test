// agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/RolePermission.java
package com.superprogrammer.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("role_permissions")
public class RolePermission {

    /**
     * 复合主键之一，不使用自动生成ID
     */
    @TableId(type = IdType.NONE)
    private Long roleId;

    private Long permissionId;
}
