package com.superprogrammer.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("users")
public class User extends BaseEntity {

    private String email;
    private String phone;
    private String passwordHash;
    private String role;
    private String status;
    private Boolean emailVerified;
    private Boolean phoneVerified;
    private Integer deviceLimit;
    private Integer offlineCacheMinutes;
}
