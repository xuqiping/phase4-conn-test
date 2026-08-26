// agent-platform/backend/src/test/java/com/superprogrammer/auth/it/UserUnlockIT.java
package com.superprogrammer.auth.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修复III E1（12x#2）联动 IT：暴破锁号可见 + 管理员提前解锁。
 *
 * <p>链路：① jdbc 落 LOCKED+locked_until → 登录（密码正确）显「将于 MM-dd HH:mm 自动解锁」
 * + data.lockedUntil；② admin 调 PUT /api/users/{id}/unlock → 200 → 立即可登；
 * ③ BANNED 行 unlock → 400 语义分离；④ 审计行落（module=user action=unlock）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserUnlockIT {

    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** 造用户（jdbc 直插 + 指定角色码）+ 登录拿真 accessToken。 */
    private String createUserAndLogin(String username, String roleCode) throws Exception {
        deleteUser(username);
        String bcrypt = BCrypt.hashpw(PASSWORD, BCrypt.gensalt());
        jdbc.update("INSERT INTO users (username, password, email, status, created_at, updated_at, deleted, version)"
                        + " VALUES (?, ?, ?, 'ACTIVE', NOW(), NOW(), 0, 0)",
                username, bcrypt, username + "@unlock.test");
        long userId = userIdOf(username);
        jdbc.update("INSERT INTO user_roles (user_id, role_id)"
                + " SELECT ?, id FROM roles WHERE code = ? ON CONFLICT DO NOTHING", userId, roleCode);
        return login(username, true);
    }

    /** 登录一次拿 token（expectOk=false 返回原始响应体字符串供断言话术）。 */
    private String login(String username, boolean expectOk) throws Exception {
        var actions = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "username", username, "password", PASSWORD))));
        if (!expectOk) {
            // UTF-8 显式取——getContentAsString() 默认 ISO-8859-1，中文话术断言必乱码
            return actions.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        }
        return objectMapper.readTree(actions.andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private long userIdOf(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private void deleteUser(String username) {
        jdbc.update("DELETE FROM users WHERE username = ?", username);
    }

    @Test
    void lockedAccount_loginShowsUnlockTime_adminUnlockRestoresLogin() throws Exception {
        String adminToken = createUserAndLogin("e1_admin", "admin");
        createUserAndLogin("e1_victim", "user");
        long victimId = userIdOf("e1_victim");

        // ① 模拟暴破锁号落库（11x C7 路径产物：LOCKED + locked_until=+15min + ban 标记）
        jdbc.update("UPDATE users SET status = 'LOCKED', locked_until = NOW() + INTERVAL '15 minutes',"
                + " ban_reason = '暴破锁定' WHERE id = ?", victimId);

        String body = login("e1_victim", false);
        assertThat(body).contains("自动解锁");
        assertThat(body).contains("lockedUntil");
        assertThat(body).doesNotContain("accessToken");   // 未发 token

        // ② admin 提前解锁 → 200 → 立即可登
        mockMvc.perform(put("/api/users/{id}/unlock", victimId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("已解锁，该用户可立即登录"));
        String token = login("e1_victim", true);
        assertThat(token).isNotBlank();

        // ④ 审计行落（异步写咽喉，轮询等一小会）
        for (int i = 0; i < 20; i++) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE module = 'user' AND action = 'unlock'"
                            + " AND target_id = ?", Integer.class, String.valueOf(victimId));
            if (n != null && n > 0) break;
            Thread.sleep(200);
        }
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE module = 'user' AND action = 'unlock'"
                        + " AND target_id = ?", Integer.class, String.valueOf(victimId));
        assertThat(auditCount).isGreaterThan(0);
    }

    @Test
    void bannedAccount_unlockRejected400_semanticSeparation() throws Exception {
        String adminToken = createUserAndLogin("e1_admin2", "admin");
        createUserAndLogin("e1_bad", "user");
        long badId = userIdOf("e1_bad");
        jdbc.update("UPDATE users SET status = 'BANNED', ban_reason = '违规' WHERE id = ?", badId);

        mockMvc.perform(put("/api/users/{id}/unlock", badId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("封禁/禁用账号请用「启用」动作恢复，解锁仅针对自动锁定"));
    }

    @Test
    void unlock_withoutPermission_forbidden() throws Exception {
        String userToken = createUserAndLogin("e1_plain", "user");
        createUserAndLogin("e1_target", "user");
        long targetId = userIdOf("e1_target");

        mockMvc.perform(put("/api/users/{id}/unlock", targetId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }
}
