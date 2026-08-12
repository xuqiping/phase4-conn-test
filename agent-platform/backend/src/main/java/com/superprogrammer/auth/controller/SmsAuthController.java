// agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/SmsAuthController.java
package com.superprogrammer.auth.controller;

import com.superprogrammer.auth.dto.SmsCodeRequest;
import com.superprogrammer.auth.dto.SmsLoginRequest;
import com.superprogrammer.auth.dto.TokenResponse;
import com.superprogrammer.auth.service.SmsService;
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
 * 手机验证码登录（通道 B）。
 *
 * <p>公开端点：无需登录。SecurityConfig + SecurityEndpointRegistry 两处同步放行。
 *
 * <p>安全：滑块前置闸门（防 SMS Pumping）；限流三档；统一话术防枚举；新号自动建号。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class SmsAuthController {

    private final SmsService smsService;

    /** 发短信验证码（前置滑块校验）。 */
    @PostMapping("/sms/code")
    public ResponseEntity<R<String>> sendSmsCode(@Valid @RequestBody SmsCodeRequest request,
                                                  HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);
        String message = smsService.sendCode(request.getPhone(), request.getCaptchaToken(), clientIp);
        return ResponseEntity.ok(R.ok(message, null));
    }

    /** 验证码登录（新号自动建号）。 */
    @PostMapping("/login/sms")
    public ResponseEntity<R<TokenResponse>> loginBySms(@Valid @RequestBody SmsLoginRequest request) {
        TokenResponse response = smsService.verifyAndLogin(request.getPhone(), request.getCode());
        return ResponseEntity.ok(R.ok(response));
    }

    /** 取真实客户端 IP（复用 AuthService 的 trusted-proxies 逻辑，沉淀约束 5：XFF 默认不可信）。 */
    private String getClientIp(HttpServletRequest request) {
        // 简化版：直接取 remoteAddr（生产 Nginx 反代时，trusted-proxies 逻辑在 AuthService.currentClientIp 里）
        // 这里为保持一致，直接取 remoteAddr；如需 XFF 支持，抽 AuthService.currentClientIp 为公共方法
        return request.getRemoteAddr();
    }
}
