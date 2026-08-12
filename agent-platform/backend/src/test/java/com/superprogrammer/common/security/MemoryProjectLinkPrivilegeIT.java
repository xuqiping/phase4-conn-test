package com.superprogrammer.common.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B2 示范 · memory 域（项目↔项目授权 link）：A 的项目 P1 授权给 C 的项目 P2（PENDING link），
 * 无关用户 B 审批 → 403（数据层 ACL：MemoryProjectLinkService.isOwnerOrAdmin 查 memory_project_members）。
 * 记忆 link 端点无平台权限注解（登录即可达），403 必来自服务层归属判定。
 */
class MemoryProjectLinkPrivilegeIT extends AbstractPrivilegeIT {

    private static final String USER_A = "priv_memlink_a";
    private static final String USER_B = "priv_memlink_b";
    private static final String USER_C = "priv_memlink_c";
    private static final String P1_NAME = "priv-it-memlink-child";
    private static final String P2_NAME = "priv-it-memlink-parent";

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM memory_project_links WHERE granted_by IN (?,?,?)",
                userIdOfQuiet(USER_A), userIdOfQuiet(USER_B), userIdOfQuiet(USER_C));
        jdbc.update("DELETE FROM memory_project_members WHERE project_id IN"
                + " (SELECT id FROM projects WHERE name IN (?,?))", P1_NAME, P2_NAME);
        jdbc.update("DELETE FROM projects WHERE name IN (?,?)", P1_NAME, P2_NAME);
        deleteUser(USER_A);
        deleteUser(USER_B);
        deleteUser(USER_C);
    }

    private Long userIdOfQuiet(String username) {
        return jdbc.query("SELECT id FROM users WHERE username = ?", rs -> rs.next() ? rs.getLong(1) : null, username);
    }

    @Test
    void crossUserLinkApproveForbidden() throws Exception {
        String tokenB = createUserAndLogin(USER_B);
        String tokenC = createUserAndLogin(USER_C);
        createUserAndLogin(USER_A);
        long aId = userIdOf(USER_A);
        long cId = userIdOf(USER_C);

        // given：A 的 child 项目 P1、C 的 parent 项目 P2（含 OWNER 成员行），P1→P2 PENDING link
        jdbc.update("DELETE FROM projects WHERE name IN (?,?)", P1_NAME, P2_NAME);
        jdbc.update("INSERT INTO projects (name, created_by, created_at, updated_at, deleted, version)"
                + " VALUES (?, ?, NOW(), NOW(), 0, 0)", P1_NAME, aId);
        jdbc.update("INSERT INTO projects (name, created_by, created_at, updated_at, deleted, version)"
                + " VALUES (?, ?, NOW(), NOW(), 0, 0)", P2_NAME, cId);
        Long p1 = jdbc.queryForObject("SELECT id FROM projects WHERE name = ?", Long.class, P1_NAME);
        Long p2 = jdbc.queryForObject("SELECT id FROM projects WHERE name = ?", Long.class, P2_NAME);
        jdbc.update("INSERT INTO memory_project_members (project_id, user_id, role, status, created_at, updated_at)"
                + " VALUES (?, ?, 'OWNER', 'ACTIVE', NOW(), NOW())", p1, aId);
        jdbc.update("INSERT INTO memory_project_members (project_id, user_id, role, status, created_at, updated_at)"
                + " VALUES (?, ?, 'OWNER', 'ACTIVE', NOW(), NOW())", p2, cId);
        jdbc.update("INSERT INTO memory_project_links (parent_project_id, child_project_id, granted_by, status,"
                + " created_by, created_at, updated_at, deleted, version)"
                + " VALUES (?, ?, ?, 'PENDING', ?, NOW(), NOW(), 0, 0)", p2, p1, aId, aId);
        Long linkId = jdbc.queryForObject(
                "SELECT id FROM memory_project_links WHERE parent_project_id = ? AND child_project_id = ? AND deleted = 0",
                Long.class, p2, p1);

        // when/then：无关用户 B 审批 → 403（非 404/500）
        assertForbidden(mockMvc.perform(post("/api/chat/memory/links/{id}/approve", linkId).with(bearer(tokenB))));
        // 正向对照：parent owner C 可审批
        mockMvc.perform(post("/api/chat/memory/links/{id}/approve", linkId).with(bearer(tokenC)))
                .andExpect(status().isOk());
    }
}
