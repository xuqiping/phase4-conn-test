package com.superprogrammer.common.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B2 示范 · kb 域：A 的 PRIVATE 知识库，B（持 knowledge:read 平台权限）读 → 403
 * （数据层 ACL：KnowledgeBaseService.get 归属校验）。B 有平台权限故 403 必来自归属层。
 */
class KnowledgeBasePrivilegeIT extends AbstractPrivilegeIT {

    private static final String USER_A = "priv_kb_a";
    private static final String USER_B = "priv_kb_b";
    private static final String KB_NAME = "priv-it-kb-越权示范";

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM knowledge_bases WHERE name = ?", KB_NAME);
        deleteUser(USER_A);
        deleteUser(USER_B);
    }

    @Test
    void crossUserKbReadForbidden() throws Exception {
        String tokenA = createUserAndLogin(USER_A);
        String tokenB = createUserAndLogin(USER_B);
        long ownerId = userIdOf(USER_A);

        // given：A 的 PRIVATE 知识库（直插行，绕开 knowledge:write 平台权限——测的是数据层）
        jdbc.update("DELETE FROM knowledge_bases WHERE name = ?", KB_NAME);
        jdbc.update("INSERT INTO knowledge_bases (tenant_id, name, visibility, embedding_model, status,"
                        + " created_by, created_at, updated_at, deleted, version)"
                        + " VALUES (1, ?, 'PRIVATE', 'doubao', 'ACTIVE', ?, NOW(), NOW(), 0, 0)",
                KB_NAME, ownerId);
        Long kbId = jdbc.queryForObject(
                "SELECT id FROM knowledge_bases WHERE name = ?", Long.class, KB_NAME);

        // when/then：B 读 → 403（非 404/500）
        assertForbidden(mockMvc.perform(get("/api/knowledge/bases/{id}", kbId).with(bearer(tokenB))));
        // 正向对照：创建者 A 可读
        mockMvc.perform(get("/api/knowledge/bases/{id}", kbId).with(bearer(tokenA)))
                .andExpect(status().isOk());
    }
}
