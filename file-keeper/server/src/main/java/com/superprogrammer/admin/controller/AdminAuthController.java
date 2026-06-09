package com.superprogrammer.admin.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.user.dto.AuthResponse;
import com.superprogrammer.user.dto.LoginRequest;
import com.superprogrammer.user.dto.RefreshTokenRequest;
import com.superprogrammer.user.service.UserAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final UserAuthService userAuthService;

    @PostMapping("/login")
    public R<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(userAuthService.adminLogin(request));
    }

    @PostMapping("/refresh")
    public R<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return R.ok(userAuthService.adminRefresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public R<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        userAuthService.logout(request.refreshToken());
        return R.ok();
    }
}
