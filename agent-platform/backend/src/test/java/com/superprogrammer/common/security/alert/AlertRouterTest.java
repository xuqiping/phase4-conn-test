// agent-platform/backend/src/test/java/com/superprogrammer/common/security/alert/AlertRouterTest.java
package com.superprogrammer.common.security.alert;

import com.superprogrammer.common.security.rule.SecurityRule;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AlertRouter 单测（11x 加固 P4-C11）：severity 分流 + 总闸 + 异常吞。
 */
@ExtendWith(MockitoExtension.class)
class AlertRouterTest {

    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private WebhookNotifier webhookNotifier;
    @Mock
    private MediumBatcher mediumBatcher;

    private AlertRouter router;

    @BeforeEach
    void setUp() {
        router = new AlertRouter(systemSettingService, webhookNotifier, mediumBatcher);
        lenient().when(systemSettingService.getBoolean(WebhookNotifier.KEY_ALERT_ENABLED, true)).thenReturn(true);
    }

    private SecurityRule.Verdict verdict(String severity) {
        return new SecurityRule.Verdict("TEST_EVT", severity, 42L, "1.2.3.4", "{}", "NONE");
    }

    @Test
    void critical_sendsImmediate() {
        router.dispatch(verdict("CRITICAL"));
        verify(webhookNotifier).postMarkdown(anyString(), anyString());
        verifyNoInteractions(mediumBatcher);
    }

    @Test
    void high_sendsImmediate() {
        router.dispatch(verdict("HIGH"));
        verify(webhookNotifier).postMarkdown(anyString(), anyString());
    }

    @Test
    void medium_goesToBatch() {
        router.dispatch(verdict("MEDIUM"));
        verify(mediumBatcher).add(any());
        verifyNoInteractions(webhookNotifier);
    }

    @Test
    void low_notSent() {
        router.dispatch(verdict("LOW"));
        verifyNoInteractions(webhookNotifier, mediumBatcher);
    }

    @Test
    void masterSwitchOff_nothingSent() {
        when(systemSettingService.getBoolean(WebhookNotifier.KEY_ALERT_ENABLED, true)).thenReturn(false);

        router.dispatch(verdict("CRITICAL"));

        verifyNoInteractions(webhookNotifier, mediumBatcher);
    }

    @Test
    void notifierThrows_swallowed() {
        doThrow(new RuntimeException("webhook down")).when(webhookNotifier).postMarkdown(anyString(), anyString());

        router.dispatch(verdict("HIGH")); // 不抛
    }
}
