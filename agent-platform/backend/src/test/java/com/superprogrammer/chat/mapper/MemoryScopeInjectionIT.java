package com.superprogrammer.chat.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.common.config.TestSecurityConfig;
import com.superprogrammer.engine.OrchestrationEngine;
import com.superprogrammer.engine.context.ExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 项目记忆 scope 注入 HTTP 集成测（V33，真实 PG）。
 * <p>两条路径都验「scope 决定哪条记忆进 LLM 上下文」：
 * <ul>
 *   <li>{@code POST /api/chat/memories/preview}（确定性注入预览，前端面板对照用）—— globalOnly / projectOnly /
 *       combined / allOff 四种开关组合；</li>
 *   <li>{@code POST /api/chat/messages/stream}（真 streaming 端点）—— @MockBean 引擎抓 ExecutionContext，
 *       断言 system prompt「用户记忆」按 session scope 注入（reactor-nio：scope 在 lambda 外解析，controller
 *       用普通 Thread + blockLast，非 nio 线程，已规避 .block() 陷阱）。</li>
 * </ul>
 * 检索模式强制 LLM_FULL_CONTEXT（默认）+ 记忆数 ≤ 阈值 → buildFullContext 纯 SQL 格式化，不触 embed/LLM（免 key 确定性）。
 * TestAuthFilter principal=1L → getCurrentUserId()=1（admin）。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Import(TestSecurityConfig.class)
class MemoryScopeInjectionIT {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private OrchestrationEngine orchestrationEngine;

    private static final String G_VAL = "ALPHA_GLOBAL_VAL";
    private static final String P_VAL = "BETA_PROJECT_VAL";
    private static final long U = 1L;   // TestAuthFilter principal = 1L (admin)

    private long projId;

    @BeforeEach
    void seed() {
        clean();
        // 强制 LLM_FULL_CONTEXT（默认值，显式 UPSERT 防他测残留）→ buildFullContext 纯 SQL，免 embed/LLM
        jdbc.update("""
                INSERT INTO system_settings (setting_key, setting_value, description) VALUES
                  ('rag.memory.retrieval-mode','LLM_FULL_CONTEXT','IT 强制：全量灌入免 LLM')
                ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value
                """);
        projId = jdbc.queryForObject(
                "INSERT INTO projects (name, created_by) VALUES (?,?) RETURNING id",
                Long.class, "scopeit_proj", U);
        // 全局记忆 + 项目记忆（挂 projId）— 记忆 key 含 query token "scopeit" 便于 keyword 兜底，但 fullContext 不需要命中
        insertMemory(U, "scopeit_global", G_VAL, true, null);
        insertMemory(U, "scopeit_proj", P_VAL, false, projId);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void preview_globalOnly_injectsGlobalOnly() {
        JsonNode data = preview(true, List.of());
        assertTrue(data.path("context").asText("").contains(G_VAL), "globalOnly → 注入全局记忆");
        assertFalse(data.path("context").asText("").contains(P_VAL), "globalOnly → 不注入项目记忆");
        assertEquals(1L, data.path("totalMemories").asLong(-1), "globalOnly totalMemories=1");
    }

    @Test
    void preview_projectOnly_injectsProjectOnly() {
        JsonNode data = preview(false, List.of(projId));
        assertTrue(data.path("context").asText("").contains(P_VAL), "projectOnly → 注入项目记忆");
        assertFalse(data.path("context").asText("").contains(G_VAL), "projectOnly → 不注入全局记忆");
        assertEquals(1L, data.path("totalMemories").asLong(-1), "projectOnly totalMemories=1");
    }

    @Test
    void preview_allOff_injectsNothing() {
        JsonNode data = preview(false, List.of());
        String ctx = data.path("context").asText("");
        assertTrue(ctx.isEmpty(), "全关 → 不注入（SCOPE_FILTER 1=0）");
        assertEquals(0L, data.path("totalMemories").asLong(-1), "全关 totalMemories=0");
    }

    @Test
    void preview_combined_injectsBoth() {
        JsonNode data = preview(true, List.of(projId));
        String ctx = data.path("context").asText("");
        assertTrue(ctx.contains(G_VAL) && ctx.contains(P_VAL), "combined → 全局+项目都注入");
        assertEquals(2L, data.path("totalMemories").asLong(-1), "combined totalMemories=2");
    }

    @Test
    void stream_scopeGatesInjectedSystemPrompt() throws Exception {
        // 抓 streaming 注入给引擎的 ExecutionContext（buildMemoryContext 在 line 414 拼进 system msg）。
        // 用 thenAnswer + AtomicReference 抓（ArgumentCaptor.capture() 在 when() 里返 null 会注册成
        // executeStream(null,_) 误 stub，真调用不匹配 → 返 null → blockLast NPE → fallback，经典坑）。
        java.util.concurrent.atomic.AtomicReference<ExecutionContext> captured = new java.util.concurrent.atomic.AtomicReference<>();
        when(orchestrationEngine.executeStream(org.mockito.ArgumentMatchers.any(ExecutionContext.class), anyString()))
                .thenAnswer(inv -> {
                    captured.set(inv.getArgument(0));
                    return Flux.just(StreamEvent.chunk("OK"), StreamEvent.done());
                });

        // projectOnly scope（memIncludeGlobal=false + 读 projId）+ ragEnabled=true → ragOn → 触发注入
        Map<String, Object> req = baseChatRequest();
        req.put("ragEnabled", true);
        req.put("memIncludeGlobal", false);
        req.put("memReadProjectIds", List.of(projId));
        req.put("projectId", projId);

        String body = rest.postForObject(url("/api/chat/messages/stream"), req, String.class);
        assertNotNull(body, "stream 端点正常返回 SSE");

        assertNotNull(captured.get(), "executeStream 被调（streaming 路径跑通，未走 fallback）");
        String systemMsg = captured.get().getMessageHistory().stream()
                .filter(m -> "system".equals(m.getRole()))
                .map(m -> m.getContent() == null ? "" : m.getContent())
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(systemMsg.contains("用户记忆"), "system prompt 含「用户记忆」注入块");
        assertTrue(systemMsg.contains(P_VAL), "projectOnly scope → 注入项目记忆");
        assertFalse(systemMsg.contains(G_VAL), "projectOnly scope → 全局记忆被 scope 滤掉");
    }

    // ============================ helpers ============================

    private JsonNode preview(boolean includeGlobal, List<Long> projectIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("query", "scopeit hello");
        body.put("includeGlobal", includeGlobal);
        body.put("projectIds", projectIds);
        String json = rest.postForObject(url("/api/chat/memories/preview"), body, String.class);
        try {
            return new ObjectMapper().readTree(json).path("data");
        } catch (Exception e) {
            fail("preview 响应解析失败: " + json, e);
            return null;
        }
    }

    private Map<String, Object> baseChatRequest() {
        Map<String, Object> req = new HashMap<>();
        req.put("message", "scopeit hello");
        return req;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void insertMemory(long user, String key, String value, boolean isGlobal, Long project) {
        jdbc.update("""
                INSERT INTO user_memories
                  (user_id, category, memory_key, memory_key_zh, memory_value, source, confidence,
                   block_label, embedding, conflict_id, entities, is_global, created_at, updated_at)
                VALUES (?, 'FACT', ?, ?, ?, 'INFERRED', 0.9, 'b', NULL, NULL, NULL, ?, now(), now())
                """,
                user, key, key + "_zh", value, isGlobal);
        if (project != null) {
            Long mid = jdbc.queryForObject(
                    "SELECT id FROM user_memories WHERE user_id=? AND memory_key=?",
                    Long.class, user, key);
            jdbc.update("INSERT INTO user_memory_projects (memory_id, project_id) VALUES (?,?)", mid, project);
        }
    }

    private void clean() {
        jdbc.update("DELETE FROM user_memory_projects WHERE memory_id IN "
                + "(SELECT id FROM user_memories WHERE user_id=? AND memory_key LIKE 'scopeit_%')", U);
        jdbc.update("DELETE FROM user_memories WHERE user_id=? AND memory_key LIKE 'scopeit_%'", U);
        // stream 测会建会话+消息：先删消息（FK）再删会话
        jdbc.update("DELETE FROM chat_messages WHERE session_id IN "
                + "(SELECT id FROM chat_sessions WHERE user_id=? AND title LIKE 'scopeit%')", U);
        jdbc.update("DELETE FROM chat_sessions WHERE user_id=? AND title LIKE 'scopeit%'", U);
        jdbc.update("DELETE FROM projects WHERE created_by=? AND name='scopeit_proj'", U);
    }
}
