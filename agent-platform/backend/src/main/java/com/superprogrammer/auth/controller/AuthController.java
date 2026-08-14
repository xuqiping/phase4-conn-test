// agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/AuthController.java
package com.superprogrammer.auth.controller;

import com.superprogrammer.auth.dto.*;
import com.superprogrammer.auth.service.AuthService;
import com.superprogrammer.auth.service.EmailService;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final EmailService emailService;

    @PostMapping("/register")
    public ResponseEntity<R<Void>> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);

        // 认证系统增强 Chunk B：注册成功后异步发验证邮件（事务外，防阿里云调用拉长事务）。
        // 降级：邮件发送失败不阻断注册（用户可登录后在设置页重发）。
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            try {
                emailService.sendVerifyEmail(null, request.getEmail());
            } catch (Exception e) {
                log.warn("注册后发验证邮件失败（降级，不影响注册成功） email={} : {}", request.getEmail(), e.toString());
            }
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(R.ok("注册成功，请查收验证邮件（未验证邮箱不可用于找回密码）", null));
    }

    @PostMapping("/login")
    public ResponseEntity<R<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse response = authService.login(request);
        return ResponseEntity.ok(R.ok(response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<R<TokenResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        TokenResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(R.ok(response));
    }

    // ==================== 安全体系 S5 · SEC-FR-006（A6 TOTP） ====================

    /** 两步登录第二屏：mfaToken + 验证码/恢复码 → 双 token（permitAll，mfaToken 即凭证）。 */
    @PostMapping("/mfa/verify")
    public ResponseEntity<R<TokenResponse>> verifyMfa(@Valid @RequestBody MfaVerifyRequest request) {
        TokenResponse response = authService.verifyMfa(request);
        return ResponseEntity.ok(R.ok(response));
    }

    /** 当前用户 MFA 状态（设置页渲染：是否已绑定/平台是否建议绑定）。 */
    @GetMapping("/mfa/status")
    public ResponseEntity<R<MfaStatusResponse>> mfaStatus() {
        Long userId = currentUserId();
        return ResponseEntity.ok(R.ok(MfaStatusResponse.builder()
                .bound(authService.isMfaBound(userId))
                .required(authService.isTotpRequired())
                .build()));
    }

    /** 发起绑定：返回 secret + otpauth URI（确认前不生效，可重复发起覆盖 pending）。 */
    @PostMapping("/mfa/bind")
    public ResponseEntity<R<MfaBindResponse>> mfaBind() {
        Long userId = currentUserId();
        return ResponseEntity.ok(R.ok(authService.startMfaBind(userId)));
    }

    /** 确认绑定：验证器首个 code 验 secret 转正 + 发放 8 组一次性恢复码（明文仅此一次）。 */
    @PostMapping("/mfa/bind/confirm")
    public ResponseEntity<R<MfaBindResponse>> mfaBindConfirm(@Valid @RequestBody MfaConfirmRequest request) {
        Long userId = currentUserId();
        return ResponseEntity.ok(R.ok(authService.confirmMfaBind(userId, request.getCode())));
    }

    /** 解绑：需当前有效验证码/恢复码（防会话被劫后直接拆掉第二因素）。 */
    @PostMapping("/mfa/unbind")
    public ResponseEntity<R<Void>> mfaUnbind(@Valid @RequestBody MfaConfirmRequest request) {
        Long userId = currentUserId();
        authService.unbindMfa(userId, request.getCode());
        return ResponseEntity.ok(R.ok("解绑成功", null));
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }

    @PostMapping("/logout")
    public ResponseEntity<R<Void>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) LogoutRequest logoutRequest) {
        String accessToken = null;
        String refreshToken = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            accessToken = authHeader.substring(7);
        }

        if (logoutRequest != null) {
            refreshToken = logoutRequest.getRefreshToken();
        }

        authService.logout(accessToken, refreshToken);
        return ResponseEntity.ok(R.ok("登出成功", null));
    }

    @GetMapping("/me")
    public ResponseEntity<R<UserVO>> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long userId = (Long) authentication.getPrincipal();
        UserVO userVO = authService.getCurrentUser(userId);
        return ResponseEntity.ok(R.ok(userVO));
    }
}
