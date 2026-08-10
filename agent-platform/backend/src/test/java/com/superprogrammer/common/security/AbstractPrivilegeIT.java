package com.superprogrammer.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 越权测试脚手架（安全体系 S2 · B2，SEC-FR-011）：「A 建 → B 读/改/删 → 断言 403」统一骨架。
 *
 * <p>用法：继承本类，{@link #createUserAndLogin} 造两个真实用户（走真注册/登录/真安全链，
 * 不 Import TestSecurityConfig——否则 permitAll 测试链把 403 全吞了），A 建行/建资源，
 * B 调端点，{@link #assertForbidden} 断言。
 *
 * <p>红线（SEC-FR-011）：越权必须显式 <b>403</b>——404（藏资源）让攻击者无法区分
 * 「不存在 vs 无权」是次优但可以接受，<b>500 绝不可接受</b>（=归属校验缺失/异常逃逸）。
 *
 * <p>注意：B 必须持有目标端点的方法级权限，403 才来自数据层归属校验（否则测的是注解层）。
 * 各域示范见 FilePrivilegeIT / KnowledgeBasePrivilegeIT / AssetProjectPrivilegeIT。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractPrivilegeIT {

    protected static final String TEST_PASSWORD = "password123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    /** 造用户（jdbc 直插 + 绑 user 角色，绕开注册限流）+ 登录拿真实 accessToken（走真登录链）。 */
    protected String createUserAndLogin(String username) throws Exception {
        deleteUser(username);
        String bcrypt = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt());
        jdbc.update("INSERT INTO users (username, password, email, status, created_at, updated_at, deleted, version)"
                        + " VALUES (?, ?, ?, 'ACTIVE', NOW(), NOW(), 0, 0)",
                username, bcrypt, username + "@privilege.test");
        long userId = userIdOf(username);
        jdbc.update("INSERT INTO user_roles (user_id, role_id)"
                + " SELECT ?, id FROM roles WHERE code = 'user' ON CONFLICT DO NOTHING", userId);

        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    protected long userIdOf(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    /** 清理测试用户（user_roles 随 ON DELETE CASCADE 连带清）。 */
    protected void deleteUser(String username) {
        jdbc.update("DELETE FROM users WHERE username = ?", username);
    }

    /** B2 红线断言：403 且业务码 403（显式拒绝，非 404 藏资源 / 非 500 异常逃逸）。 */
    protected void assertForbidden(ResultActions actions) throws Exception {
        actions.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /** Authorization: Bearer 头。 */
    protected RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }
}
