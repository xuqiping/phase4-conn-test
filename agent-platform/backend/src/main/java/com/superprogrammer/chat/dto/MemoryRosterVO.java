package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 计划12 · I2 · 项目记忆花名册行（总体设计 §3.6/§3.7）。
 * <p>
 * {@code GET /api/chat/memory/projects/{pid}/roster} 返项目全部成员（含 DEPARTED 已离开，保交接），
 * 供前端配 ACL 授权矩阵 + 召回 scope 人员多选。{@code name} 为空时前端回退 {@code username}。
 *
 * @see com.superprogrammer.chat.mapper.MemoryProjectMemberMapper#findRoster
 */
@Data
@Builder
public class MemoryRosterVO {
    private Long userId;
    /** 登录账号（users.username） */
    private String username;
    /** 显示名/真实姓名（users.name，可空） */
    private String name;
    /** OWNER / ADMIN / MEMBER */
    private String role;
    /** ACL 配置权（owner 兜底 true；admin 须此 flag） */
    private Boolean recallAdmin;
    /** ACTIVE 在职 / DEPARTED 已离开（不删行保交接） */
    private String status;
    /** 离职时间（DEPARTED 才有，召回标注「已离开人员·用户名·时间」用） */
    private OffsetDateTime departedAt;
}
