package com.superprogrammer.common.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B2 示范 · media 生图域：A 的生图任务，B（临时授予 media:gen 平台权限）读取 → 403
 * （数据层归属校验：MediaGenQueryService.ensureOwnership——非 owner/非 admin 显式 FORBIDDEN）。
 * 平台权限临时授予 + 用后回收，保证 403 只能来自数据层归属校验。
 */
class MediaGenTaskPrivilegeIT extends AbstractPrivilegeIT {

    private static final String USER_A = "priv_mgen_a";
    private static final String USER_B = "priv_mgen_b";

    @BeforeAll
    void grantMediaGenToUserRole() {
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id)"
                + " SELECT r.id, p.id FROM roles r, permissions p"
                + " WHERE r.code = 'user' AND p.code = 'media:gen' ON CONFLICT DO NOTHING");
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM role_permissions WHERE role_id = (SELECT id FROM roles WHERE code = 'user')"
                + " AND permission_id = (SELECT id FROM permissions WHERE code = 'media:gen')");
        jdbc.update("DELETE FROM media_gen_tasks WHERE model = 'priv-it-model'");
        deleteUser(USER_A);
        deleteUser(USER_B);
    }

    @Test
    void crossUserMediaTaskReadForbidden() throws Exception {
        String tokenA = createUserAndLogin(USER_A);
        String tokenB = createUserAndLogin(USER_B);
        long aId = userIdOf(USER_A);

        // given：A 的生图任务（直插行，绕开提交链路——测的是读取咽喉点）
        jdbc.update("INSERT INTO media_gen_tasks (user_id, provider_id, model, task_type, status,"
                + " request_config, created_at, updated_at)"
                + " VALUES (?, 7, 'priv-it-model', 'TEXT2IMAGE', 'SUCCEEDED', '{}'::jsonb, NOW(), NOW())", aId);
        Long taskId = jdbc.queryForObject(
                "SELECT max(id) FROM media_gen_tasks WHERE model = 'priv-it-model'", Long.class);

        // when/then：B 读 A 的任务 → 403（非 404/500）
        assertForbidden(mockMvc.perform(get("/api/media/tasks/{id}", taskId).with(bearer(tokenB))));
        // 正向对照：owner A 可读
        mockMvc.perform(get("/api/media/tasks/{id}", taskId).with(bearer(tokenA)))
                .andExpect(status().isOk());
    }
}
