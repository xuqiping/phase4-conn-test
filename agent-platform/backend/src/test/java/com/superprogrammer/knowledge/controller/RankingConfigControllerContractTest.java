package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** RAG-FR-04/08：Ranking 写入口必须同时具备权限和审计契约。 */
class RankingConfigControllerContractTest {

    @Test
    void defaultAndKbWriteEndpointsAreGuardedAndAudited() throws Exception {
        Method defaultWrite = RankingConfigController.class.getMethod(
                "updateDefault", com.superprogrammer.knowledge.dto.RankingConfigUpdateRequest.class);
        assertEquals("role:manage", defaultWrite.getAnnotation(RequirePermission.class).value());
        assertNotNull(defaultWrite.getAnnotation(AuditLog.class));
        assertEquals("/admin/ranking-config", defaultWrite.getAnnotation(PutMapping.class).value()[0]);

        Method kbWrite = RankingConfigController.class.getMethod(
                "updateForKb", Long.class, com.superprogrammer.knowledge.dto.RankingConfigUpdateRequest.class);
        assertEquals("knowledge:write", kbWrite.getAnnotation(RequirePermission.class).value());
        assertNotNull(kbWrite.getAnnotation(AuditLog.class));
        assertEquals("/bases/{kbId}/ranking-config", kbWrite.getAnnotation(PutMapping.class).value()[0]);
    }

    @Test
    void readEndpointsAreAlsoPermissionGuarded() throws Exception {
        Method defaultRead = RankingConfigController.class.getMethod("getDefault");
        assertEquals("role:manage", defaultRead.getAnnotation(RequirePermission.class).value());
        assertEquals("/admin/ranking-config", defaultRead.getAnnotation(GetMapping.class).value()[0]);

        Method kbRead = RankingConfigController.class.getMethod("getForKb", Long.class);
        assertEquals("knowledge:read", kbRead.getAnnotation(RequirePermission.class).value());
    }
}
