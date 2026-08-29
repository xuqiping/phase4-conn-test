package com.superprogrammer.llm.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.llm.dto.LlmProviderCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 16x 契约：全局大模型供应商全系端点必须挂独立码 llm:config（不再借道 role:manage），
 * 使「大模型配置员」角色可精细授权，且 admin 失去设置里配大模型的权限。
 */
class LlmControllerContractTest {

    @Test
    void providerEndpointsAllUseLlmConfigCode() throws Exception {
        assertEquals("llm:config", perm(LlmController.class.getMethod("listProviders")));
        assertEquals("llm:config", perm(LlmController.class.getMethod("createProvider", LlmProviderCreateRequest.class)));
        assertEquals("llm:config", perm(LlmController.class.getMethod("updateProvider", Long.class, LlmProviderCreateRequest.class)));
        assertEquals("llm:config", perm(LlmController.class.getMethod("deleteProvider", Long.class)));
        assertEquals("llm:config", perm(LlmController.class.getMethod("testConnection", Long.class)));
        assertEquals("llm:config", perm(LlmController.class.getMethod("testEmbedding", Long.class)));
        assertEquals("llm:config", perm(LlmController.class.getMethod("testRerank", Long.class)));
        assertEquals("llm:config", perm(LlmController.class.getMethod("reloadProviders")));
        assertEquals("llm:config", perm(LlmController.class.getMethod("exportProviders",
                com.superprogrammer.llm.dto.ProviderExportRequest.class)));
        assertEquals("llm:config", perm(LlmController.class.getMethod("importProviders", java.util.List.class)));
    }

    /**
     * 修复VIII B4（VIII-5）：导出改 POST + body 密码——密码绝不进 URL（nginx 日志面）；
     * @AuditLog 保留（明文 key 外流必须可追溯），旧 GET 端点删除（同仓同发版）。
     */
    @Test
    void exportIsPostWithPasswordBodyAndAudited() throws Exception {
        Method export = LlmController.class.getMethod("exportProviders",
                com.superprogrammer.llm.dto.ProviderExportRequest.class);
        PostMapping post = export.getAnnotation(PostMapping.class);
        assertNotNull(post, "导出必须为 POST（body 携带密码）");
        assertEquals("/providers/export", post.value()[0]);
        assertNull(export.getAnnotation(GetMapping.class), "旧 GET 导出端点必须删除");
        AuditLog audit = export.getAnnotation(AuditLog.class);
        assertNotNull(audit, "@AuditLog 必须保留（失败尝试也须可追溯）");
        assertEquals("provider_export", audit.action());
    }

    @Test
    void mediaProviderTestAndModelDefaultsAlsoUseLlmConfigCode() throws Exception {
        Method mediaTest = com.superprogrammer.media.controller.MediaGenController.class
                .getMethod("testMediaProvider", Long.class);
        assertEquals("llm:config", perm(mediaTest));

        Method defaultsRead = com.superprogrammer.system.controller.SystemSettingController.class
                .getMethod("getLlmModelDefaults");
        assertEquals("llm:config", perm(defaultsRead));

        Method defaultsWrite = com.superprogrammer.system.controller.SystemSettingController.class
                .getMethod("updateLlmModelDefaults",
                        com.superprogrammer.system.dto.LlmModelDefaultsUpdateRequest.class);
        assertEquals("llm:config", perm(defaultsWrite));
    }

    private static String perm(Method m) {
        RequirePermission rp = m.getAnnotation(RequirePermission.class);
        return rp == null ? null : rp.value();
    }
}
