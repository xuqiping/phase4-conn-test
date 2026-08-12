// agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/PasswordResetController.java
package com.superprogrammer.auth.controller;

import com.superprogrammer.auth.dto.PasswordForgotRequest;
import com.superprogrammer.auth.dto.PasswordResetRequest;
import com.superprogrammer.auth.service.PasswordResetService;
import com.superprogrammer.common.result.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 找回密码（通道 D）。
 *
 * <p>公开端点：无需登录。SecurityConfig + SecurityEndpointRegistry 两处同步放行。
 *
 * <p>安全：统一话术防枚举；reset token 单次有效；重置后踢所有会话。
 */
@RestController
@RequestMapping("/api/auth/password")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    /** 发起找回密码（统一话术"若账号存在，重置链接/码已发送"）。 */
    @PostMapping("/forgot")
    public ResponseEntity<R<String>> forgot(@Valid @RequestBody PasswordForgotRequest request,
                                             HttpServletRequest httpRequest) {
        String message = passwordResetService.forgot(
                request.getIdentifier(), request.getChannel(), httpRequest.getRemoteAddr());
        return ResponseEntity.ok(R.ok(message, null));
    }

    /** 重置密码（校验 token/码 + 新密码 + 踢会话）。 */
    @PostMapping("/reset")
    public ResponseEntity<R<Void>> reset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.reset(request.getToken(), request.getNewPassword(),
                request.getChannel(), request.getPhone());
        return ResponseEntity.ok(R.ok("密码重置成功，请使用新密码登录", null));
    }
}
