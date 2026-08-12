package com.superprogrammer.common.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B2 示范 · asset 域：A 的资产项目，B（临时授予 asset:write 平台权限）读 → 403
 * （数据层 ACL：AssetAclService.loadAccessible 三判——非 owner/非成员/非 admin）。
 * 平台权限临时授予 + 用后回收，保证 403 只能来自数据层归属校验。
 */
class AssetProjectPrivilegeIT extends AbstractPrivilegeIT {

    private static final String USER_A = "priv_asset_a";
    private static final String USER_B = "priv_asset_b";
    private static final String PROJECT_NAME = "priv-it-asset-越权示范";

    @BeforeAll
    void grantAssetWriteToUserRole() {
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id)"
                + " SELECT r.id, p.id FROM roles r, permissions p"
                + " WHERE r.code = 'user' AND p.code = 'asset:write' ON CONFLICT DO NOTHING");
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM role_permissions WHERE role_id = (SELECT id FROM roles WHERE code = 'user')"
                + " AND permission_id = (SELECT id FROM permissions WHERE code = 'asset:write')");
        jdbc.update("DELETE FROM asset_projects WHERE name = ?", PROJECT_NAME);
        deleteUser(USER_A);
        deleteUser(USER_B);
    }

    @Test
    void crossUserAssetProjectReadForbidden() throws Exception {
        String tokenA = createUserAndLogin(USER_A);
        String tokenB = createUserAndLogin(USER_B);
        long ownerId = userIdOf(USER_A);

        // given：A 的资产项目（直插行，绕开创建链路——测的是读取咽喉点）
        jdbc.update("DELETE FROM asset_projects WHERE name = ?", PROJECT_NAME);
        jdbc.update("INSERT INTO asset_projects (owner_id, name, created_by, created_at, updated_at, deleted, version)"
                + " VALUES (?, ?, ?, NOW(), NOW(), 0, 0)", ownerId, PROJECT_NAME, ownerId);
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM asset_projects WHERE name = ?", Long.class, PROJECT_NAME);

        // when/then：B 读 → 403（非 404/500）
        assertForbidden(mockMvc.perform(get("/api/assets/projects/{id}", projectId).with(bearer(tokenB))));
        // 正向对照：owner A 可读
        mockMvc.perform(get("/api/assets/projects/{id}", projectId).with(bearer(tokenA)))
                .andExpect(status().isOk());
    }
}
