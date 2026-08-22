package com.superprogrammer.system.controller;

import com.superprogrammer.auth.security.JwtAuthenticationFilter;
import com.superprogrammer.search.service.WebSearchService;
import com.superprogrammer.system.service.SystemSettingService;
import com.superprogrammer.llm.service.LlmProviderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 计划12 · H'-3 · 旧端点失效契约测试。
 * <p>
 * H'-2/H'-3 删除 legacy 记忆栈后：
 * <ul>
 *   <li>{@code MemoryController}（/api/chat/memories/* 全族）整类已删 → 路由不存在；</li>
 *   <li>{@code SystemSettingController} 的 3 个旧维护端点（/rag-memory/backfill-entities |
 *       reextract-entities | cleanup-memory-residue）已随 {@code MemoryService} 依赖解除删除。</li>
 * </ul>
 * 断言：这些旧端点不再返回 2xx 成功（实际走全局异常处理返 4xx/5xx，证明旧业务通路已断）。
 * 反向断言：保留端点 /rag-memory GET 仍可路由（非 4xx/5xx），确认删除未误伤。
 */
@WebMvcTest(controllers = SystemSettingController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
class SystemSettingLegacyEndpoint404Test {

    @Autowired MockMvc mvc;
    @MockBean SystemSettingService service;
    @MockBean WebSearchService webSearchService;
    @MockBean LlmProviderService llmProviderService;
    /** 认证系统增强（08-13 auto-sync）给 controller 构造新增的依赖——切片补 mock */
    @MockBean com.superprogrammer.auth.service.AuthChannelSettingService authChannelSettingService;
    /** 12x 邮件通道测试发信端点给构造新增的依赖——切片补 mock */
    @MockBean com.superprogrammer.auth.service.EmailService emailService;

    // ---- 已删的 MemoryController 全族（代表）→ 不再 2xx ----

    @Test
    void deletedMemoryController_list_gone() throws Exception {
        mvc.perform(get("/api/chat/memories"))
                .andExpect(r -> assertGone(r.getResponse().getStatus(), "/api/chat/memories"));
    }

    @Test
    void deletedMemoryController_incident_gone() throws Exception {
        mvc.perform(get("/api/chat/memories/incident"))
                .andExpect(r -> assertGone(r.getResponse().getStatus(), "/api/chat/memories/incident"));
    }

    // ---- 已删的 SystemSettingController 3 个旧维护端点 → 不再 2xx ----

    @Test
    void removed_backfillEntities_gone() throws Exception {
        mvc.perform(post("/api/system/settings/rag-memory/backfill-entities"))
                .andExpect(r -> assertGone(r.getResponse().getStatus(), "backfill-entities"));
    }

    @Test
    void removed_reextractEntities_gone() throws Exception {
        mvc.perform(post("/api/system/settings/rag-memory/reextract-entities"))
                .andExpect(r -> assertGone(r.getResponse().getStatus(), "reextract-entities"));
    }

    @Test
    void removed_cleanupMemoryResidue_gone() throws Exception {
        mvc.perform(post("/api/system/settings/rag-memory/cleanup-memory-residue"))
                .andExpect(r -> assertGone(r.getResponse().getStatus(), "cleanup-memory-residue"));
    }

    // ---- 保留端点未误伤：/rag-memory GET 仍可解析（2xx，路由没被误删） ----

    @Test
    void kept_ragMemoryGet_stillMapped() throws Exception {
        mvc.perform(get("/api/system/settings/rag-memory"))
                .andExpect(r -> assertTrue(r.getResponse().getStatus() < 400,
                        "保留端点 /rag-memory 应可路由，实际: " + r.getResponse().getStatus()));
    }

    private static void assertGone(int status, String label) {
        assertTrue(status >= 400,
                "旧端点 " + label + " 应已失效（4xx/5xx），实际: " + status + "（仍 2xx=删除未生效）");
    }
}
