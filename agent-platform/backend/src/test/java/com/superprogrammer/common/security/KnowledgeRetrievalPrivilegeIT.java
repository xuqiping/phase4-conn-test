package com.superprogrammer.common.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全体系 S3 · Step 5（SEC-FR-057）：KB <b>检索</b>越权三路回归（区别于 S2 KnowledgeBasePrivilegeIT 测
 * KB 行级读——本类测 RAG 检索链路的数据面 ACL）：
 *
 * <ol>
 *   <li>REST 面：<code>/api/knowledge/retrieve</code> B（持 knowledge:read）带 A 的私有 kbId → 403
 *       （RagRetrievalService canRead 前置，在 embedding 之前，无 LLM 依赖）。</li>
 *   <li>sidecar 回调凭据：<code>/api/runtime/callbacks/nodes/execute</code> 无/错 X-Runtime-Token → 401
 *       （RuntimeCallbackSecurityFilter fail-closed）。</li>
 *   <li>sidecar 回调归户：body 伪造 userId=B、execution 属 A、节点 config 要 B 的私有 KB →
 *       trustedUserId 反查=A → resolveNodeKbs 按 A 权限求交 → effective 空 → abstain
 *       「未配置可访问的知识库范围」——伪造的 B 权限未被采用。若放行 body userId 则会带上 B 的 KB
 *       进入 retrieveEvidence（无 embedding key 必失败），故 abstain 即 ACL 生效证明。</li>
 * </ol>
 *
 * <p>回调 token 经 {@code @TestPropertySource} 注入（{@code runtime.callback.token}），而非 plan 原案的
 * {@code @EnabledIfEnvironmentVariable(RUNTIME_CALLBACK_TOKEN)}——后者本地无 env 时永久 skip=死测试，
 * IT 库固定密钥即可每次真跑（偏离 plan 已记录开发进度）。</p>
 */
@TestPropertySource(properties = "runtime.callback.token=priv-it-callback-secret")
class KnowledgeRetrievalPrivilegeIT extends AbstractPrivilegeIT {

    private static final String USER_A = "priv_rag_a";
    private static final String USER_B = "priv_rag_b";
    private static final String KB_A_NAME = "priv-it-rag-kb-A";
    private static final String KB_B_NAME = "priv-it-rag-kb-B";
    private static final String WF_NAME = "priv-it-rag-workflow";
    private static final String CALLBACK_TOKEN = "priv-it-callback-secret";

    @AfterAll
    void cleanup() {
        cleanupDomainRows();
        deleteUser(USER_A);
        deleteUser(USER_B);
    }

    /** 每测前清域行：workflows.owner FK 挡 deleteUser（测试间造的用户 id 会被上一测的行引用）。 */
    @org.junit.jupiter.api.BeforeEach
    void cleanupDomainRows() {
        jdbc.update("DELETE FROM execution_logs WHERE workflow_id IN"
                + " (SELECT id FROM workflows WHERE name = ?)", WF_NAME);
        jdbc.update("DELETE FROM workflows WHERE name = ?", WF_NAME);
        jdbc.update("DELETE FROM knowledge_bases WHERE name IN (?, ?)", KB_A_NAME, KB_B_NAME);
    }

    /** B（持 knowledge:read）检索 A 的私有 KB → 403（canRead 在 embedding 之前，无 LLM 依赖）。 */
    @Test
    void crossUserRetrieveForbidden() throws Exception {
        createUserAndLogin(USER_A);
        String tokenB = createUserAndLogin(USER_B);
        long ownerA = userIdOf(USER_A);
        Long kbA = insertPrivateKb(KB_A_NAME, ownerA);

        // when/then：B 检索 → 403（业务码 403，非 404/500）
        assertForbidden(mockMvc.perform(post("/api/knowledge/retrieve").with(bearer(tokenB))
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of(
                        "kbId", kbA, "query", "越权探测", "generateAnswer", false)))));
    }

    /** 回调无 token → 401（fail-closed）。 */
    @Test
    void callbackWithoutTokenRejected() throws Exception {
        createUserAndLogin(USER_A);
        mockMvc.perform(post("/api/runtime/callbacks/nodes/execute")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "executionId", "1", "sourceType", "RETRIEVAL"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        // 错 token 同拒
        mockMvc.perform(post("/api/runtime/callbacks/nodes/execute").with(request -> {
                    request.addHeader("X-Runtime-Token", "wrong-token");
                    return request;
                })
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "executionId", "1", "sourceType", "RETRIEVAL"))))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 回调 body 伪造 userId=B、execution 属 A、节点 config 要 B 的私有 KB →
     * 反查 trustedUserId=A → A 读不了 B 的 KB → effective 空 → abstain「未配置可访问」。
     * 伪造值若被采用会带 B 的 KB 走 retrieveEvidence（IT 无 embedding key 必败），故 abstain 即证明。
     */
    @Test
    void callbackForgedUserIdStillScopedToExecutionOwner() throws Exception {
        createUserAndLogin(USER_A);
        createUserAndLogin(USER_B);
        long ownerA = userIdOf(USER_A);
        long ownerB = userIdOf(USER_B);
        insertPrivateKb(KB_B_NAME, ownerB);   // B 的私有 KB（节点 config 想借回调借道读）
        long executionId = insertExecutionOwnedBy(ownerA);

        Map<String, Object> nodeConfig = Map.of(
                "kbIds", List.of(kbIdOf(KB_B_NAME)),
                "query", "伪造身份借道检索");
        String body = objectMapper.writeValueAsString(Map.of(
                "executionId", String.valueOf(executionId),
                "sourceType", "RETRIEVAL",
                "userId", ownerB,                    // 伪造：execution 其实在 A 名下
                "metadata", Map.of("nodeConfig", nodeConfig)));

        mockMvc.perform(post("/api/runtime/callbacks/nodes/execute").with(request -> {
                    request.addHeader("X-Runtime-Token", CALLBACK_TOKEN);
                    return request;
                })
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.output.abstained").value(true))
                .andExpect(jsonPath("$.data.output.text").value("未配置可访问的知识库范围。"));
    }

    /** 缺 executionId（无法反查归属）→ 400 拒绝，不留匿名检索口子。 */
    @Test
    void callbackMissingExecutionRejected() throws Exception {
        createUserAndLogin(USER_A);
        mockMvc.perform(post("/api/runtime/callbacks/nodes/execute").with(request -> {
                    request.addHeader("X-Runtime-Token", CALLBACK_TOKEN);
                    return request;
                })
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sourceType", "RETRIEVAL", "userId", 1))))
                .andExpect(status().isBadRequest());
    }

    // ============================ seed ============================

    private Long insertPrivateKb(String name, long ownerId) {
        jdbc.update("DELETE FROM knowledge_bases WHERE name = ?", name);
        jdbc.update("INSERT INTO knowledge_bases (tenant_id, name, visibility, embedding_model, status,"
                        + " created_by, created_at, updated_at, deleted, version)"
                        + " VALUES (1, ?, 'PRIVATE', 'doubao', 'ACTIVE', ?, NOW(), NOW(), 0, 0)",
                name, ownerId);
        return kbIdOf(name);
    }

    private Long kbIdOf(String name) {
        return jdbc.queryForObject("SELECT id FROM knowledge_bases WHERE name = ?", Long.class, name);
    }

    /** workflow（rag_enabled=true 跳过全局门控）+ execution_logs（triggered_by=owner）。 */
    private long insertExecutionOwnedBy(long ownerId) {
        jdbc.update("DELETE FROM execution_logs WHERE workflow_id IN"
                + " (SELECT id FROM workflows WHERE name = ?)", WF_NAME);
        jdbc.update("DELETE FROM workflows WHERE name = ?", WF_NAME);
        jdbc.update("INSERT INTO workflows (name, status, owner_id, created_by, created_at, updated_at,"
                        + " deleted, version, rag_enabled)"
                        + " VALUES (?, 'PUBLISHED', ?, ?, NOW(), NOW(), 0, 0, true)",
                WF_NAME, ownerId, ownerId);
        Long workflowId = jdbc.queryForObject(
                "SELECT id FROM workflows WHERE name = ?", Long.class, WF_NAME);
        jdbc.update("INSERT INTO execution_logs (workflow_id, workflow_name, triggered_by, status,"
                        + " started_at, created_at, updated_at, deleted, version)"
                        + " VALUES (?, ?, ?, 'RUNNING', NOW(), NOW(), NOW(), 0, 0)",
                workflowId, WF_NAME, ownerId);
        return jdbc.queryForObject(
                "SELECT id FROM execution_logs WHERE workflow_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, workflowId);
    }
}
