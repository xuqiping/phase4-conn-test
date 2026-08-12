// agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/CredentialController.java
package com.superprogrammer.auth.controller;

import com.superprogrammer.auth.dto.BindCredentialRequest;
import com.superprogrammer.auth.dto.ChangePasswordRequest;
import com.superprogrammer.auth.dto.CredentialVO;
import com.superprogrammer.auth.dto.UnbindCredentialRequest;
import com.superprogrammer.auth.entity.UserCredential;
import com.superprogrammer.auth.service.CredentialService;
import com.superprogrammer.auth.service.EmailService;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 账号安全设置（Chunk G）：当前登录用户的凭证管理 + 改密。
 *
 * <p>所有端点需登录态（SecurityConfig {@code anyRequest().authenticated()} 覆盖）；
 * userId 一律从 SecurityContext principal 取，不接受请求参数传入（防越权旁路）。
 * 在 {@link com.superprogrammer.common.security.SecurityEndpointRegistry#AUTHENTICATED_ONLY_REVIEWED}
 * 登记「仅登录即可」（userId 取自 SecurityContext，无入参旁路）。
 *
 * <p>安全语义：
 * <ul>
 *   <li>绑定邮箱：建 EMAIL 凭证 verified=FALSE → 异步触发激活邮件（失败不阻断绑定，用户可在登录页重发）</li>
 *   <li>解绑：至少留一种可用凭证（防账号失联），PASSWORD 不可解绑</li>
 *   <li>改密：验旧密码 + PasswordPolicy + 踢所有会话（强制重登）</li>
 *   <li>凭证列表 identifier 脱敏（手机/邮箱），防前端/日志明文回显</li>
 *   <li>绑/解绑/改密均标 {@code @AuditLog} 留痕（detail 仅参数摘要，严禁密码明文）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialService credentialService;
    /** 绑定邮箱后触发激活邮件（Controller 层编排，避免 CredentialService ↔ EmailService 循环依赖）。 */
    private final EmailService emailService;

    /** 当前登录用户凭证列表（设置页展示，identifier 脱敏）。 */
    @GetMapping("/credentials")
    public ResponseEntity<R<List<CredentialVO>>> list() {
        Long userId = currentUserId();
        return ResponseEntity.ok(R.ok(credentialService.listByUserId(userId)));
    }

    /**
     * 绑定邮箱：建 EMAIL 凭证 verified=FALSE → 异步触发激活邮件。
     *
     * <p>激活邮件发送失败不阻断绑定（凭证已建，用户可在登录页 /resend/email 重发）。
     */
    @AuditLog(module = "auth", action = "credential_bind", targetType = "credential")
    @PostMapping("/credential/bind-email")
    public ResponseEntity<R<Void>> bindEmail(@Valid @RequestBody BindCredentialRequest request) {
        Long userId = currentUserId();
        UserCredential cred = credentialService.bindEmail(userId, request.getEmail());
        // 触发激活邮件（异步语义：失败仅 WARN，不阻断——用户可重发）
        boolean sent = emailService.sendVerifyEmail(userId, cred.getIdentifier());
        if (!sent) {
            log.warn("绑定邮箱后激活邮件发送失败(不阻断，用户可重发) userId={}", userId);
        }
        return ResponseEntity.ok(R.ok("绑定成功，请查收激活邮件完成验证", null));
    }

    /**
     * 解绑凭证：至少留一种可用凭证，PASSWORD 不可解绑。
     */
    @AuditLog(module = "auth", action = "credential_unbind", targetType = "credential")
    @PostMapping("/credential/unbind")
    public ResponseEntity<R<Void>> unbind(@Valid @RequestBody UnbindCredentialRequest request) {
        Long userId = currentUserId();
        credentialService.unbind(userId, request.getCredentialType());
        return ResponseEntity.ok(R.ok("解绑成功", null));
    }

    /**
     * 修改密码：验旧密码 + PasswordPolicy + 踢所有会话。
     *
     * <p>改密成功后当前 token 即刻失效（会话被踢），前端收到成功响应后应主动跳转登录页。
     */
    @AuditLog(module = "auth", action = "password_change", targetType = "credential")
    @PostMapping("/password/change")
    public ResponseEntity<R<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        Long userId = currentUserId();
        credentialService.changePassword(userId, request.getOldPassword(), request.getNewPassword());
        return ResponseEntity.ok(R.ok("密码修改成功，请使用新密码重新登录", null));
    }

    /** 从 SecurityContext 取当前登录用户 id（principal = userId Long）。 */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new com.superprogrammer.common.exception.BusinessException(
                    com.superprogrammer.common.exception.ErrorCode.UNAUTHORIZED, "未登录");
        }
        return (Long) auth.getPrincipal();
    }
}
