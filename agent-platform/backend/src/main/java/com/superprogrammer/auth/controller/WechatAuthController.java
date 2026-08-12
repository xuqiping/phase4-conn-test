// agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/WechatAuthController.java
package com.superprogrammer.auth.controller;

import com.superprogrammer.auth.dto.TokenResponse;
import com.superprogrammer.auth.service.WechatAuthService;
import com.superprogrammer.common.result.R;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 微信扫码登录（通道 C）。
 *
 * <p>公开端点：无需登录。SecurityConfig + SecurityEndpointRegistry 两处同步放行。
 *
 * <p>流程：
 * <ol>
 *   <li>前端调 /login/wechat/redirect 拿授权 URL → window.location 跳转微信</li>
 *   <li>用户扫码确认 → 微信回调 /login/wechat/callback?code=xxx&state=xxx</li>
 *   <li>后端换 JWT → 重定向到前端（token 用 URL fragment，不进 server log/referer）</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/auth/login/wechat")
@RequiredArgsConstructor
@Slf4j
public class WechatAuthController {

    private final WechatAuthService wechatAuthService;

    /** 前端落地页地址（回调后重定向到这里带 token）。 */
    @Value("${app.auth.wechat.frontend-base:http://localhost:5173}")
    private String frontendBase;

    /** 生成微信授权跳转 URL（前端 GET 后 window.location 跳转）。 */
    @GetMapping("/redirect")
    public ResponseEntity<R<String>> redirect() {
        String url = wechatAuthService.buildAuthorizeUrl();
        return ResponseEntity.ok(R.ok(url));
    }

    /**
     * 微信回调（GET，微信重定向到这里）。
     * 成功 → 重定向前端 {@code /#/login?token=xxx&refreshToken=xxx}（fragment 不进 log）；
     * 失败 → 重定向前端 {@code /#/login?error=wechat_failed}。
     */
    @GetMapping("/callback")
    public void callback(@RequestParam("code") String code,
                         @RequestParam("state") String state,
                         HttpServletResponse response) throws IOException {
        try {
            TokenResponse tokenResponse = wechatAuthService.handleCallback(code, state);
            // token 用 URL fragment 传递（# 后部分不进 server log/referer）
            String redirectUrl = UriComponentsBuilder.fromHttpUrl(frontendBase)
                    .fragment("/login?token=" + URLEncoder.encode(tokenResponse.getAccessToken(), StandardCharsets.UTF_8)
                            + "&refreshToken=" + URLEncoder.encode(tokenResponse.getRefreshToken(), StandardCharsets.UTF_8))
                    .build().toUriString();
            response.sendRedirect(redirectUrl);
        } catch (Exception e) {
            log.warn("微信回调失败: {}", e.toString());
            String errorUrl = UriComponentsBuilder.fromHttpUrl(frontendBase)
                    .fragment("/login?error=wechat_failed")
                    .build().toUriString();
            response.sendRedirect(errorUrl);
        }
    }
}
