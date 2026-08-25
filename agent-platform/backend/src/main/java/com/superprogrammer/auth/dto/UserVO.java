// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/UserVO.java
package com.superprogrammer.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    private Long id;
    private String username;
    private String name;
    /** 账号备注（D1/12x-1，≤128 字：注册/资料/管理员可维护，管理列表 keyword 命中） */
    private String remark;
    private String primaryDepartmentName;
    private String email;
    private String avatar;
    private String status;
    /** 封禁/禁用/锁定原因（11x 加固 V104，仅管理端列表展示用） */
    private String banReason;
    /** 自动锁定到期时间（11x 加固 V104） */
    private OffsetDateTime lockedUntil;
    private OffsetDateTime lastLoginAt;
    private OffsetDateTime createdAt;
    private List<String> roles;
    private List<String> permissions;
}
