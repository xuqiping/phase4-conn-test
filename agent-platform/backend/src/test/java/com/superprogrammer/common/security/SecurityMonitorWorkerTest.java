// agent-platform/backend/src/test/java/com/superprogrammer/common/security/SecurityMonitorWorkerTest.java
package com.superprogrammer.common.security;

import com.superprogrammer.common.security.alert.AlertRouter;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.common.security.rule.SecurityRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SecurityMonitorWorker 单测（11x 加固 P3-C8）：规则遍历/system 跳过/去重后处置/异常隔离。
 */
@ExtendWith(MockitoExtension.class)
class SecurityMonitorWorkerTest {

    @Mock
    private SecurityEventService securityEventService;
    @Mock
    private AutoResponder autoResponder;
    @Mock
    private AlertRouter alertRouter;
    @Mock
    private SecurityRule ruleA;
    @Mock
    private SecurityRule ruleB;

    private SecurityMonitorWorker worker;

    private static final SecurityRule.Verdict HIT = new SecurityRule.Verdict(
            "TEST_EVT", "HIGH", 42L, "1.2.3.4", "{}", "NONE");

    @BeforeEach
    void setUp() {
        worker = new SecurityMonitorWorker(List.of(ruleA, ruleB), securityEventService, autoResponder, alertRouter);
    }

    private ApplicationSecurityEvent event(boolean system) {
        return new ApplicationSecurityEvent(this, ApplicationSecurityEvent.KIND_CHAT_MESSAGE,
                42L, "1.2.3.4", Map.of("content", "x"), system);
    }

    @Test
    void systemEvent_skipsAllRules() {
        worker.onApplicationSecurityEvent(event(true));

        verifyNoInteractions(ruleA, ruleB, securityEventService, autoResponder);
    }

    @Test
    void hit_recordsAndResponds() {
        when(ruleA.supports(anyString())).thenReturn(true);
        when(ruleA.evaluate(any())).thenReturn(HIT);
        when(ruleB.supports(anyString())).thenReturn(false);
        when(securityEventService.record(anyString(), anyString(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

        worker.onApplicationSecurityEvent(event(false));

        verify(securityEventService).record(eq("TEST_EVT"), eq("HIGH"), eq(42L), eq("1.2.3.4"),
                anyString(), eq("{}"), eq("NONE"));
        verify(autoResponder).execute(HIT);
    }

    @Test
    void dedupedHit_skipsResponder() {
        when(ruleA.supports(anyString())).thenReturn(true);
        when(ruleA.evaluate(any())).thenReturn(HIT);
        when(ruleB.supports(anyString())).thenReturn(false);
        // 去重窗口内重复 → record 返 false
        when(securityEventService.record(anyString(), anyString(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);

        worker.onApplicationSecurityEvent(event(false));

        verify(autoResponder, never()).execute(any());
    }

    @Test
    void ruleThrows_otherRulesStillRun() {
        when(ruleA.supports(anyString())).thenReturn(true);
        when(ruleA.evaluate(any())).thenThrow(new RuntimeException("rule boom"));
        when(ruleB.supports(anyString())).thenReturn(true);
        when(ruleB.evaluate(any())).thenReturn(HIT);
        when(securityEventService.record(anyString(), anyString(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

        assertDoesNotThrow(() -> worker.onApplicationSecurityEvent(event(false)));

        verify(autoResponder).execute(HIT);
    }

    @Test
    void noHit_noRecordNoRespond() {
        when(ruleA.supports(anyString())).thenReturn(true);
        when(ruleA.evaluate(any())).thenReturn(null);
        when(ruleB.supports(anyString())).thenReturn(false);

        worker.onApplicationSecurityEvent(event(false));

        verifyNoInteractions(securityEventService, autoResponder);
    }
}
