package com.superprogrammer.llm.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.llm.dto.LlmProviderCreateRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals("llm:config", perm(LlmController.class.getMethod("exportProviders")));
        assertEquals("llm:config", perm(LlmController.class.getMethod("importProviders", java.util.List.class)));
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
