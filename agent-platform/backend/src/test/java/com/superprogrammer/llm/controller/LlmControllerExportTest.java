// agent-platform/backend/src/test/java/com/superprogrammer/llm/controller/LlmControllerExportTest.java
package com.superprogrammer.llm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.auth.service.AuthService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.llm.config.LlmConfig;
import com.superprogrammer.llm.dto.LlmProviderExportItem;
import com.superprogrammer.llm.dto.ProviderExportRequest;
import com.superprogrammer.llm.service.LlmProviderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 修复VIII B4（VIII-5）：导出 POST + 密码二次确认——
 * 密码错/缺失 → BusinessException 且不出数据（密钥零外泄）；
 * 密码对 → 校验当前登录用户、回全量条目（含明文 key）+ 附件下载头。
 * 「失败尝试也落审计」由 @AuditLog @Around 切面对异常路径记 FAIL 保证（注解保留断言在 ContractTest）。
 */
class LlmControllerExportTest {

    private LlmProviderService providerService;
    private AuthService authService;
    private LlmController controller;

    @BeforeEach
    void setUp() {
        providerService = mock(LlmProviderService.class);
        authService = mock(AuthService.class);
        controller = new LlmController(providerService, mock(LlmConfig.class),
                new ObjectMapper(), authService);
        // JwtAuthenticationFilter 语义：principal = Long userId
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(7L, "creds", "llm:config"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static ProviderExportRequest req(String password) {
        ProviderExportRequest r = new ProviderExportRequest();
        r.setPassword(password);
        return r;
    }

    @Test
    void wrongPassword_throwsAndNeverExports() {
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "密码错误"))
                .when(authService).verifyUserPassword(7L, "wrong-pw");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.exportProviders(req("wrong-pw")));

        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        // 密码错 → 明文 key 零外泄：根本不触达导出
        verify(providerService, never()).exportAll();
    }

    @Test
    void missingPassword_sameRejectionPath() {
        // 空/缺失密码与错密码同路（不设 @NotBlank——失败也要过 @AuditLog 切面留 FAIL 行）
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "密码错误"))
                .when(authService).verifyUserPassword(7L, null);

        assertThrows(BusinessException.class, () -> controller.exportProviders(req(null)));
        verify(providerService, never()).exportAll();
    }

    @Test
    void correctPassword_returnsFullItemsWithPlaintextKeyAndDownloadHeaders() throws Exception {
        LlmProviderExportItem item = new LlmProviderExportItem();
        item.setName("deepseek");
        item.setApiEndpoint("https://api.deepseek.com/v1/chat/completions");
        item.setApiKey("sk-plain-secret");
        when(providerService.exportAll()).thenReturn(List.of(item));

        ResponseEntity<byte[]> resp = controller.exportProviders(req("admin123"));

        // 复用注销同款校验：以当前登录用户 id + 明文密码调用
        verify(authService).verifyUserPassword(eq(7L), eq("admin123"));
        assertEquals(200, resp.getStatusCode().value());
        String body = new String(resp.getBody());
        assertTrue(body.contains("sk-plain-secret"), "正确密码应导出明文 key: " + body);
        assertTrue(body.contains("deepseek"));
        String disposition = resp.getHeaders().getFirst("Content-Disposition");
        assertTrue(disposition != null && disposition.startsWith("attachment;"),
                "应带附件下载头: " + disposition);
        assertEquals("application/json", resp.getHeaders().getFirst("Content-Type"));
        verify(providerService).exportAll();
    }
}
