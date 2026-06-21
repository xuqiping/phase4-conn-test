package com.superprogrammer.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("departments")
public class Department extends BaseEntity {

    private Long tenantId;

    private String name;

    private String code;

    private Long parentId;

    private String description;

    private Integer sortOrder;

    private String status;
}
