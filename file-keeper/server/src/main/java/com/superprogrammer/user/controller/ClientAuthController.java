package com.superprogrammer.user.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.user.dto.*;
import com.superprogrammer.user.service.UserAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/client/auth")
@RequiredArgsConstructor
public class ClientAuthController {

    private final UserAuthService userAuthService;

    @PostMapping("/register")
    public R<UserSummary> register(@Valid @RequestBody RegisterRequest request) {
        return R.ok(userAuthService.register(request));
    }

    @PostMapping("/login")
    public R<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(userAuthService.clientLogin(request));
    }

    @PostMapping("/refresh")
    public R<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return R.ok(userAuthService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public R<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        userAuthService.logout(request.refreshToken());
        return R.ok();
    }
}
