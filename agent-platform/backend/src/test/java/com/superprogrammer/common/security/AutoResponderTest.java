// agent-platform/backend/src/test/java/com/superprogrammer/common/security/AutoResponderTest.java
package com.superprogrammer.common.security;

import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.common.security.rule.SecurityRule;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AutoResponder 单测（11x 加固 P3-C10）：处置矩阵 + 总闸/分闸 + 降级吞异常。
 */
@ExtendWith(MockitoExtension.class)
class AutoResponderTest {

    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private BanService banService;
    @Mock
    private IpBlacklistService ipBlacklistService;
    @Mock
    private BizMetrics bizMetrics;

    private AutoResponder responder;

    @BeforeEach
    void setUp() {
        responder = new AutoResponder(systemSettingService, banService, ipBlacklistService, bizMetrics);
        // 默认：三闸全开
        lenient().when(systemSettingService.getBoolean(anyString(), anyBoolean())).thenReturn(true);
    }

    private SecurityRule.Verdict verdict(String severity, String autoAction) {
        return new SecurityRule.Verdict("TEST_EVT", severity, 42L, "1.2.3.4", "{}", autoAction);
    }

    @Test
    void critical_locksAndBlocks() {
        responder.execute(verdict("CRITICAL", "NONE"));

        verify(banService).lockAccount(42L, AutoResponder.LOCK_MINUTES, "TEST_EVT");
        verify(ipBlacklistService).autoBlock("1.2.3.4", "TEST_EVT", AutoResponder.CRITICAL_IP_BLOCK_MINUTES);
        verify(bizMetrics).accountLocked("lock");
        verify(bizMetrics).ipBlocked("AUTO");
    }

    @Test
    void high_accountLockedAction_locksOnly() {
        responder.execute(verdict("HIGH", "ACCOUNT_LOCKED"));

        verify(banService).lockAccount(eq(42L), anyInt(), anyString());
        verify(ipBlacklistService, never()).autoBlock(anyString(), anyString(), anyLong());
    }

    @Test
    void high_ipBlockedAction_blocksOnly() {
        responder.execute(verdict("HIGH", "IP_BLOCKED"));

        verify(ipBlacklistService).autoBlock("1.2.3.4", "TEST_EVT", AutoResponder.HIGH_IP_BLOCK_MINUTES);
        verify(banService, never()).lockAccount(anyLong(), anyInt(), anyString());
    }

    @Test
    void high_noAction_alertsOnly() {
        responder.execute(verdict("HIGH", "NONE"));

        verifyNoInteractions(banService);
        verifyNoInteractions(ipBlacklistService);
    }

    @Test
    void medium_alertsOnly() {
        responder.execute(verdict("MEDIUM", "ACCOUNT_LOCKED"));

        verifyNoInteractions(banService);
        verifyNoInteractions(ipBlacklistService);
    }

    @Test
    void masterSwitchOff_nothingHappens() {
        when(systemSettingService.getBoolean(AutoResponder.KEY_AUTO_ENABLED, true)).thenReturn(false);

        responder.execute(verdict("CRITICAL", "NONE"));

        verifyNoInteractions(banService);
        verifyNoInteractions(ipBlacklistService);
    }

    @Test
    void accountLockSwitchOff_criticalOnlyBlocksIp() {
        when(systemSettingService.getBoolean(AutoResponder.KEY_AUTO_ACCOUNT_LOCK, true)).thenReturn(false);

        responder.execute(verdict("CRITICAL", "NONE"));

        verify(banService, never()).lockAccount(anyLong(), anyInt(), anyString());
        verify(ipBlacklistService).autoBlock(anyString(), anyString(), anyLong());
    }

    @Test
    void ipBlockSwitchOff_criticalOnlyLocksAccount() {
        when(systemSettingService.getBoolean(AutoResponder.KEY_AUTO_IP_BLOCK, true)).thenReturn(false);

        responder.execute(verdict("CRITICAL", "NONE"));

        verify(banService).lockAccount(anyLong(), anyInt(), anyString());
        verify(ipBlacklistService, never()).autoBlock(anyString(), anyString(), anyLong());
    }

    @Test
    void nullUserId_skipsLockButBlocksIp() {
        responder.execute(new SecurityRule.Verdict("TEST_EVT", "CRITICAL", null, "1.2.3.4", "{}", "NONE"));

        verify(banService, never()).lockAccount(anyLong(), anyInt(), anyString());
        verify(ipBlacklistService).autoBlock(anyString(), anyString(), anyLong());
    }

    @Test
    void settingsDown_usesDefaultsAndSwallows() {
        when(systemSettingService.getBoolean(anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> responder.execute(verdict("CRITICAL", "NONE")));
        // 默认全开 → 仍处置
        verify(banService).lockAccount(anyLong(), anyInt(), anyString());
        verify(ipBlacklistService).autoBlock(anyString(), anyString(), anyLong());
    }
}
