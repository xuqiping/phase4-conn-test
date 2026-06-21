package com.superprogrammer.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_departments")
public class UserDepartment extends BaseEntity {

    private Long userId;

    private Long departmentId;

    private Boolean isPrimary;
}
