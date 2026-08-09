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

    /**
     * 第二轮 #5：是否已推入记忆公共池。
     * true = 所有人可在「申请召回」候选里看到本项目并发起授权申请（复用 user-grants 审批流）。
     * 仅项目 OWNER/ADMIN 可切换。
     */
    private Boolean memoryPoolPublic;
}
