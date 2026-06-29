package com.superprogrammer.auth.dingtalk.controller;

import com.superprogrammer.auth.dingtalk.dto.DingTalkLoginRequest;
import com.superprogrammer.auth.dingtalk.service.DingTalkService;
import com.superprogrammer.auth.dto.TokenResponse;
import com.superprogrammer.auth.service.AuthService;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/login")
@RequiredArgsConstructor
public class DingTalkAuthController {

    private final DingTalkService dingTalkService;
    private final AuthService authService;

    /**
     * 钉钉免登登录：前端把 authCode POST 上来，换本平台 JWT。
     */
    @PostMapping("/dingtalk")
    public ResponseEntity<R<TokenResponse>> loginByDingTalk(@Valid @RequestBody DingTalkLoginRequest request) {
        DingTalkService.DingTalkUserInfo info = dingTalkService.exchangeUser(request.getAuthCode());
        TokenResponse response = authService.loginByDingTalk(info);
        return ResponseEntity.ok(R.ok(response));
    }
}
