// agent-platform/backend/src/test/java/com/superprogrammer/auth/service/MfaServiceTest.java
package com.superprogrammer.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.auth.dto.MfaBindResponse;
import com.superprogrammer.auth.totp.TotpService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 安全体系 S5 · SEC-FR-006（A6 TOTP）绑定/校验/恢复码状态机单测。
 * 存储 mock SystemSettingService 加密 KV（真实加密由该服务自身单测覆盖）。
 */
@ExtendWith(MockitoExtension.class)
class MfaServiceTest {

    private static final String SECRET_KEY = "security.totp.secret.u.1";
    private static final String PENDING_KEY = "security.totp.pending.u.1";
    private static final String RECOVERY_KEY = "security.totp.recovery.u.1";

    @Mock
    private SystemSettingService systemSettingService;

    @Mock
    private TotpService totpService;

    private MfaService mfaService;

    @BeforeEach
    void setUp() {
        mfaService = new MfaService(systemSettingService, totpService, new ObjectMapper());
    }

    @Test
    void isBound_secretPresent_true() {
        when(systemSettingService.getDecryptedValue(SECRET_KEY)).thenReturn("BASE32SECRET");
        assertTrue(mfaService.isBound(1L));
    }

    @Test
    void isBound_noSecretOrBlank_false() {
        assertFalse(mfaService.isBound(1L));   // null
        when(systemSettingService.getDecryptedValue(SECRET_KEY)).thenReturn("");
        assertFalse(mfaService.isBound(1L));   // 空=已清空（解绑后）
    }

    @Test
    void isBound_storageFailure_degradesToFalse() {
        when(systemSettingService.getDecryptedValue(SECRET_KEY)).thenThrow(new RuntimeException("db down"));
        assertFalse(mfaService.isBound(1L));   // 不抛异常（登录链不被打死）
    }

    @Test
    void startBind_generatesSecretPendingAndUri() {
        when(systemSettingService.getDecryptedValue(SECRET_KEY)).thenReturn(null);
        when(totpService.generateSecret()).thenReturn("NEWSECRET");
        when(totpService.buildOtpauthUri(eq("NEWSECRET"), eq("admin"), anyString()))
                .thenReturn("otpauth://totp/x?secret=NEWSECRET");

        MfaBindResponse resp = mfaService.startBind(1L, "admin");

        assertEquals("NEWSECRET", resp.getSecret());
        assertTrue(resp.getOtpauthUri().contains("NEWSECRET"));
        // pending 存加密 KV（确认前不转正）
        verify(systemSettingService).upsertEncrypted(eq(PENDING_KEY), eq("NEWSECRET"), contains("u1"));
        verify(systemSettingService, never()).upsertEncrypted(eq(SECRET_KEY), anyString(), anyString());
    }

    @Test
    void startBind_alreadyBound_conflict() {
        when(systemSettingService.getDecryptedValue(SECRET_KEY)).thenReturn("EXISTING");
        assertThrows(BusinessException.class, () -> mfaService.startBind(1L, "admin"));
    }

    @Test
    void confirmBind_validCode_secretActivatedRecoveryCodesIssued() {
        when(systemSettingService.getDecryptedValue(PENDING_KEY)).thenReturn("PENDINGSECRET");
        when(totpService.verify(eq("PENDINGSECRET"), eq("123456"), anyLong())).thenReturn(true);
        when(totpService.generateRecoveryCodes()).thenReturn(Arrays.asList("aaaaa-bbbbb", "ccccc-ddddd"));
        when(totpService.sha256Hex("aaaaa-bbbbb")).thenReturn("HA");
        when(totpService.sha256Hex("ccccc-ddddd")).thenReturn("HB");

        MfaBindResponse resp = mfaService.confirmBind(1L, "123456");

        assertEquals(Arrays.asList("aaaaa-bbbbb", "ccccc-ddddd"), resp.getRecoveryCodes());
        // secret 转正 + pending 清空
        verify(systemSettingService).upsertEncrypted(eq(SECRET_KEY), eq("PENDINGSECRET"), contains("u1"));
        verify(systemSettingService).clearSettingValue(PENDING_KEY);
        // 恢复码只存 SHA-256 哈希（明文不落库）
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(systemSettingService).upsertEncrypted(eq(RECOVERY_KEY), saved.capture(), anyString());
        assertTrue(saved.getValue().contains("HA") && saved.getValue().contains("HB"));
        assertFalse(saved.getValue().contains("aaaaa-bbbbb"));
    }

    @Test
    void confirmBind_wrongCode_rejected() {
        when(systemSettingService.getDecryptedValue(PENDING_KEY)).thenReturn("PENDINGSECRET");
        when(totpService.verify(eq("PENDINGSECRET"), eq("000000"), anyLong())).thenReturn(false);

        assertThrows(BusinessException.class, () -> mfaService.confirmBind(1L, "000000"));
        verify(systemSettingService, never()).upsertEncrypted(eq(SECRET_KEY), anyString(), anyString());
    }

    @Test
    void confirmBind_noPending_rejected() {
        when(systemSettingService.getDecryptedValue(PENDING_KEY)).thenReturn(null);
        assertThrows(BusinessException.class, () -> mfaService.confirmBind(1L, "123456"));
    }

    @Test
    void verifyAndConsume_totpCode_trueWithoutRecoveryConsumption() {
        when(systemSettingService.getDecryptedValue(SECRET_KEY)).thenReturn("SECRET");
        when(totpService.verify(eq("SECRET"), eq("123456"), anyLong())).thenReturn(true);

        assertTrue(mfaService.verifyAndConsume(1L, "123456", true));
        verify(systemSettingService, never()).upsertEncrypted(eq(RECOVERY_KEY), anyString(), anyString());
    }

    @Test
    void verifyAndConsume_recoveryCode_matchOnceThenConsumed() throws Exception {
        when(systemSettingService.getDecryptedValue(SECRET_KEY)).thenReturn("SECRET");
        when(totpService.verify(eq("SECRET"), anyString(), anyLong())).thenReturn(false);
        when(totpService.sha256Hex("aaaaa-bbbbb")).thenReturn("HA");
        // 首次读取返回两条，消耗后返回一条
        when(systemSettingService.getDecryptedValue(RECOVERY_KEY))
                .thenReturn(new ObjectMapper().writeValueAsString(List.of("HA", "HB")))
                .thenReturn(new ObjectMapper().writeValueAsString(List.of("HB")));

        assertTrue(mfaService.verifyAndConsume(1L, "AAAAA-BBBBB", true));   // 大小写归一
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(systemSettingService).upsertEncrypted(eq(RECOVERY_KEY), saved.capture(), anyString());
        assertFalse(saved.getValue().contains("HA"));   // HA 已移除（一码一次）

        assertFalse(mfaService.verifyAndConsume(1L, "AAAAA-BBBBB", true)); // 第二次同码 → 失败
    }

    @Test
    void verifyAndConsume_garbageCode_false() {
        when(systemSettingService.getDecryptedValue(SECRET_KEY)).thenReturn("SECRET");
        when(totpService.verify(eq("SECRET"), anyString(), anyLong())).thenReturn(false);
        when(systemSettingService.getDecryptedValue(RECOVERY_KEY)).thenReturn("[\"HX\"]");

        assertFalse(mfaService.verifyAndConsume(1L, "zzzzz-zzzzz", true));
    }

    @Test
    void unbind_validCode_clearsState() {
        when(systemSettingService.getDecryptedValue(SECRET_KEY)).thenReturn("SECRET");
        when(totpService.verify(eq("SECRET"), eq("123456"), anyLong())).thenReturn(true);

        assertDoesNotThrow(() -> mfaService.unbind(1L, "123456"));
        verify(systemSettingService).clearSettingValue(SECRET_KEY);
        verify(systemSettingService).clearSettingValue(RECOVERY_KEY);
    }

    @Test
    void unbind_wrongCode_rejectedStateKept() {
        when(systemSettingService.getDecryptedValue(SECRET_KEY)).thenReturn("SECRET");
        when(totpService.verify(eq("SECRET"), anyString(), anyLong())).thenReturn(false);
        when(systemSettingService.getDecryptedValue(RECOVERY_KEY)).thenReturn("[\"HX\"]");

        assertThrows(BusinessException.class, () -> mfaService.unbind(1L, "000000"));
        verify(systemSettingService, never()).clearSettingValue(anyString());
    }
}
