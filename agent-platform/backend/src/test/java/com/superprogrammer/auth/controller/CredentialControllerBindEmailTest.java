package com.superprogrammer.auth.controller;

import com.superprogrammer.auth.dto.BindCredentialRequest;
import com.superprogrammer.auth.entity.UserCredential;
import com.superprogrammer.auth.service.CredentialService;
import com.superprogrammer.auth.service.EmailService;
import com.superprogrammer.auth.service.MfaService;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CredentialController.bindEmail 的 12x B4 语义单测：
 * 已绑 TOTP → 必须过码；未绑 TOTP → 直接绑（现状兼容）。
 */
@ExtendWith(MockitoExtension.class)
class CredentialControllerBindEmailTest {

    @Mock private CredentialService credentialService;
    @Mock private EmailService emailService;
    @Mock private MfaService mfaService;

    private CredentialController controller;

    @BeforeEach
    void setUp() {
        controller = new CredentialController(credentialService, emailService, mfaService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, null, java.util.List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private BindCredentialRequest req(String email, String totpCode) {
        BindCredentialRequest r = new BindCredentialRequest();
        r.setEmail(email);
        r.setTotpCode(totpCode);
        return r;
    }

    @Test
    void bindEmail_mfaBoundNoCode_throws() {
        when(mfaService.isBound(42L)).thenReturn(true);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.bindEmail(req("new@x.com", null)));
        assertTrue(ex.getMessage().contains("两步验证"));
        verifyNoInteractions(credentialService);
    }

    @Test
    void bindEmail_mfaBoundWrongCode_throws() {
        when(mfaService.isBound(42L)).thenReturn(true);
        when(mfaService.verifyAndConsume(42L, "000000", true)).thenReturn(false);
        assertThrows(BusinessException.class, () -> controller.bindEmail(req("new@x.com", "000000")));
        verifyNoInteractions(credentialService);
    }

    @Test
    void bindEmail_mfaBoundValidCode_binds() {
        when(mfaService.isBound(42L)).thenReturn(true);
        when(mfaService.verifyAndConsume(42L, "123456", true)).thenReturn(true);
        UserCredential cred = new UserCredential();
        cred.setIdentifier("new@x.com");
        when(credentialService.bindEmail(42L, "new@x.com")).thenReturn(cred);

        assertDoesNotThrow(() -> controller.bindEmail(req("new@x.com", "123456")));
        verify(credentialService).bindEmail(42L, "new@x.com");
        verify(emailService).sendVerifyEmail(42L, "new@x.com");
    }

    @Test
    void bindEmail_mfaNotBound_bindsWithoutCode() {
        when(mfaService.isBound(42L)).thenReturn(false);
        UserCredential cred = new UserCredential();
        cred.setIdentifier("new@x.com");
        when(credentialService.bindEmail(42L, "new@x.com")).thenReturn(cred);

        assertDoesNotThrow(() -> controller.bindEmail(req("new@x.com", null)));
        verify(credentialService).bindEmail(42L, "new@x.com");
        verify(mfaService, never()).verifyAndConsume(anyLong(), anyString(), anyBoolean());
    }
}
