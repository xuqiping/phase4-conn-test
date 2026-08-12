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
