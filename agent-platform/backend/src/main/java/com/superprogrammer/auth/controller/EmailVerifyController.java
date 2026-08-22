// agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/EmailVerifyController.java
package com.superprogrammer.auth.controller;

import com.superprogrammer.auth.dto.EmailVerifyRequest;
import com.superprogrammer.auth.dto.RegisterEmailCodeRequest;
import com.superprogrammer.auth.dto.ResendEmailRequest;
import com.superprogrammer.auth.service.EmailService;
import com.superprogrammer.common.result.R;
import com.superprogrammer.common.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 邮箱激活 / 重发验证邮件（通道 A）。
 *
 * <p>公开端点：无需登录（注册后激活场景）。SecurityConfig + SecurityEndpointRegistry.PUBLIC_PATHS 两处同步放行。
 *
 * <p>安全：统一话术防枚举（不区分"邮箱存在/不存在"、"已验证/未验证"）；token 单次有效用完即删；重发限流 60s。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class EmailVerifyController {

    private final EmailService emailService;
    private final ClientIpResolver clientIpResolver;

    /**
     * 邮箱激活（用户点激活链接后前端调）。
     * 审计：EmailService.verifyEmail 内部手工建行（认证类无注解范式，detail 只带 reason 码）。
     */
    @PostMapping("/verify/email")
    public ResponseEntity<R<Void>> verifyEmail(@Valid @RequestBody EmailVerifyRequest request) {
        emailService.verifyEmail(request.getToken());
        return ResponseEntity.ok(R.ok("邮箱验证成功", null));
    }

    /**
     * 重发验证邮件（注册后未收到/链接过期时）。
     * 统一话术：不泄露邮箱是否已注册、是否已验证（防枚举）。
     * 审计：EmailService.resendVerifyEmail 内部手工建行。
     */
    @PostMapping("/resend/email")
    public ResponseEntity<R<String>> resendEmail(@Valid @RequestBody ResendEmailRequest request,
                                                 HttpServletRequest httpRequest) {
        String message = emailService.resendVerifyEmail(request.getEmail(), clientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok(R.ok(message, null));
    }

    /**
     * 12x B1：注册邮箱验证码发送（注册前置闸）。
     * <p>限流：同邮箱 60s + 同 IP 10 封/h；通道未开启直接拒绝（注册必须验邮箱）。</p>
     */
    @PostMapping("/register/email-code")
    public ResponseEntity<R<Void>> sendRegisterEmailCode(@Valid @RequestBody RegisterEmailCodeRequest request,
                                                         HttpServletRequest httpRequest) {
        emailService.sendRegisterCode(request.getEmail(), clientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok(R.ok("验证码已发送，10 分钟内有效", null));
    }
}
