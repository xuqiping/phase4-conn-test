package com.superprogrammer.auth.service;

import com.superprogrammer.auth.config.AliyunMailConfig;
import com.superprogrammer.auth.config.AliyunSmsConfig;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthChannelSettingServiceTest {
    private SystemSettingService settings;
    private AuthChannelSettingService service;

    @BeforeEach
    void setUp() {
        settings = mock(SystemSettingService.class);
        AliyunMailConfig mail = new AliyunMailConfig();
        mail.setAccessKeyId("env-mail-ak");
        mail.setAccessKeySecret("env-mail-sk");
        mail.setRegion("cn-hangzhou");
        AliyunSmsConfig sms = new AliyunSmsConfig();
        sms.setAccessKeyId("env-sms-ak");
        sms.setAccessKeySecret("env-sms-sk");
        sms.setRegion("cn-hangzhou");
        service = new AuthChannelSettingService(settings, mail, sms);
    }

    @Test
    void AC_AUTH_CHANNEL_001_databaseValuesOverrideEnvironmentFallback() {
        when(settings.getSettingValue("auth.channel.mail.access-key-id")).thenReturn("db-mail-ak");
        when(settings.getDecryptedValue("auth.channel.mail.access-key-secret")).thenReturn("db-mail-sk");
        var snapshot = service.mailSnapshot();
        assertEquals("db-mail-ak", snapshot.accessKeyId());
        assertEquals("db-mail-sk", snapshot.accessKeySecret());
    }

    @Test
    void AC_AUTH_CHANNEL_002_missingDatabaseValuesUseEnvironmentFallback() {
        var snapshot = service.mailSnapshot();
        assertEquals("env-mail-ak", snapshot.accessKeyId());
        assertEquals("env-mail-sk", snapshot.accessKeySecret());
        assertEquals("cn-hangzhou", snapshot.region());
    }

    @Test
    void AC_AUTH_CHANNEL_003_viewNeverReturnsSecretPlaintext() {
        when(settings.getDecryptedValue("auth.channel.sms.access-key-secret")).thenReturn("db-secret");
        var view = service.getSettings();
        assertTrue(view.getSms().getSecretConfigured());
        assertFalse(view.toString().contains("db-secret"));
    }

    @Test
    void AC_AUTH_CHANNEL_004_nullSecretDoesNotChangeAndBlankClearsOverride() {
        var request = new com.superprogrammer.system.dto.AuthChannelSettingsUpdateRequest();
        var sms = new com.superprogrammer.system.dto.AuthChannelSettingsUpdateRequest.Sms();
        request.setSms(sms);
        service.update(request);
        verify(settings, never()).upsertEncrypted(anyString(), anyString(), anyString());
        verify(settings, never()).clearSettingValue(anyString());

        sms.setAccessKeySecret("   ");
        service.update(request);
        verify(settings).clearSettingValue("auth.channel.sms.access-key-secret");
    }

    @Test
    void AC_AUTH_CHANNEL_005_nonBlankSecretIsEncryptedBySystemSettingService() {
        var request = new com.superprogrammer.system.dto.AuthChannelSettingsUpdateRequest();
        var mail = new com.superprogrammer.system.dto.AuthChannelSettingsUpdateRequest.Mail();
        mail.setAccessKeySecret("new-secret");
        request.setMail(mail);
        service.update(request);
        verify(settings).upsertEncrypted("auth.channel.mail.access-key-secret", "new-secret",
                "邮件 AccessKey Secret（AES 加密）");
    }
}
