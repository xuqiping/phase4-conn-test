// agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/User.java
package com.superprogrammer.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("users")
public class User extends BaseEntity {

    private String username;

    private String password;

    private String email;

    private String avatar;

    private String status;

    private LocalDateTime lastLoginAt;
}
