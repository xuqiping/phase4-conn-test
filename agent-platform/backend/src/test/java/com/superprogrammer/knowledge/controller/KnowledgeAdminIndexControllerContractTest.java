package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeAdminIndexControllerContractTest {

    @Test
    void exposesProtectedStatusRebuildSwitchAndRollbackEndpoints() throws Exception {
        assertEndpoint("indexStatus", GetMapping.class, "/indexes/{kbId}");
        assertEndpoint("rebuildIndex", PostMapping.class, "/indexes/{kbId}/rebuild");
        assertEndpoint("switchIndex", PostMapping.class, "/indexes/{kbId}/switch");
        assertEndpoint("rollbackIndex", PostMapping.class, "/indexes/{kbId}/rollback");
        for (Method method : KnowledgeAdminController.class.getDeclaredMethods()) {
            if (method.getName().matches("indexStatus|rebuildIndex|switchIndex|rollbackIndex")) {
                RequirePermission permission = method.getAnnotation(RequirePermission.class);
                assertNotNull(permission, method.getName());
                assertEquals("knowledge:manage", permission.value());
            }
        }
    }

    private static <A extends java.lang.annotation.Annotation> void assertEndpoint(
            String methodName, Class<A> annotationType, String path) {
        Method method = java.util.Arrays.stream(KnowledgeAdminController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName)).findFirst().orElseThrow();
        A annotation = method.getAnnotation(annotationType);
        assertNotNull(annotation);
        String[] values = annotation instanceof GetMapping get ? get.value() : ((PostMapping) annotation).value();
        assertEquals(path, values[0]);
    }
}
