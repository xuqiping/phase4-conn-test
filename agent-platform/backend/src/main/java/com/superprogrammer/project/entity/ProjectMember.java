package com.superprogrammer.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目成员（共享授权，V33）。role ∈ OWNER/EDITOR/VIEWER。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "project_members", autoResultMap = true)
public class ProjectMember extends BaseEntity {

    private Long projectId;

    private Long userId;

    /** OWNER / EDITOR / VIEWER */
    private String role;
}
