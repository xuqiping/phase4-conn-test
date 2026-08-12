// agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/CaptchaController.java
package com.superprogrammer.auth.controller;

import com.anji.captcha.model.vo.CaptchaVO;
import com.superprogrammer.auth.service.CaptchaService;
import com.superprogrammer.common.result.R;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 滑块验证码（AJ-Captcha）。
 *
 * <p>公开端点：无需登录（发码/登录前置闸门）。SecurityConfig + SecurityEndpointRegistry 两处同步放行。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class CaptchaController {

    private final CaptchaService captchaService;

    /** 获取滑块验证码（前端渲染用）。 */
    @GetMapping("/captcha")
    public ResponseEntity<R<CaptchaVO>> getCaptcha() {
        return ResponseEntity.ok(R.ok(captchaService.get()));
    }

    /** 校验滑块轨迹（前端滑块后提交）。 */
    @PostMapping("/captcha/verify")
    public ResponseEntity<R<Void>> verifyCaptcha(@RequestBody CaptchaVerifyRequest request) {
        captchaService.verify(request.getCaptchaVerification());
        return ResponseEntity.ok(R.ok("验证成功", null));
    }

    @Data
    public static class CaptchaVerifyRequest {
        @NotBlank(message = "captchaVerification 不能为空")
        private String captchaVerification;
    }
}
