package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 记忆专属成员（V47 计划12）。独立于 Agent 模块 project_members（旧表不动）。
 * 无 deleted——离职置 status=DEPARTED 不删行（保成员历史/交接）。
 * role：OWNER/ADMIN/MEMBER；recall_admin=true 的 admin 才能配 ACL（owner 兜底全读）。
 */
@Data
@TableName("memory_project_members")
public class MemoryProjectMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private Long userId;
    private String role;             // OWNER / ADMIN / MEMBER
    private Boolean recallAdmin;     // ACL 配置权（owner 兜底；admin 须此 flag）
    private String status;           // ACTIVE / DEPARTED
    private OffsetDateTime departedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
