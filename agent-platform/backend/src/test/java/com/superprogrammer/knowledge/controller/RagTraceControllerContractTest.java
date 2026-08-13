package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class RagTraceControllerContractTest {

    @Test
    void detailAndReverseLookup_areAdminGuardedAndAudited() throws Exception {
        Method detail = RagRetrievalLogController.class.getMethod("traceDetail", String.class);
        assertEquals("knowledge:manage", detail.getAnnotation(RequirePermission.class).value());
        assertNotNull(detail.getAnnotation(AuditLog.class));
        assertEquals("/traces/{traceId}", detail.getAnnotation(GetMapping.class).value()[0]);

        Method resolve = RagRetrievalLogController.class.getMethod(
                "resolveTrace", String.class, Long.class, Long.class);
        assertEquals("knowledge:manage", resolve.getAnnotation(RequirePermission.class).value());
        assertNotNull(resolve.getAnnotation(AuditLog.class));
    }
}
