package com.superprogrammer.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_module_entitlements")
public class UserModuleEntitlement extends BaseEntity {

    private Long userId;
    private String moduleCode;
    private Boolean enabled;
    private OffsetDateTime expiresAt;
}
