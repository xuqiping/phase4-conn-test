package com.superprogrammer.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目（记忆 scope 容器，V33）。用户私有 + 可共享（project_members）。
 * owner = {@link #getCreatedBy()}。记忆经 user_memory_projects 多对多挂到项目。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "projects", autoResultMap = true)
public class Project extends BaseEntity {

    private String name;

    private String description;

    private String icon;

    private Integer sortOrder;
}
