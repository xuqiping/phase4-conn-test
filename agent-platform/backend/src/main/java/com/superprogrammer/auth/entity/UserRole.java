// agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/UserRole.java
package com.superprogrammer.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_roles")
public class UserRole {

    private Long userId;

    private Long roleId;
}
