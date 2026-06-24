package com.superprogrammer.admin.controller;

import com.superprogrammer.admin.dto.UserReviewRequest;
import com.superprogrammer.admin.service.AdminUserService;
import com.superprogrammer.common.PageResult;
import com.superprogrammer.common.R;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.user.dto.UserSettingsUpdateRequest;
import com.superprogrammer.user.dto.UserSummary;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public R<PageResult<UserSummary>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size
    ) {
        return R.ok(adminUserService.list(status, page, size));
    }

    @GetMapping("/{id}")
    public R<UserSummary> detail(@PathVariable Long id) {
        return R.ok(adminUserService.detail(id));
    }

    @PostMapping("/{id}/approve")
    public R<UserSummary> approve(Authentication authentication, @PathVariable Long id, @RequestBody UserReviewRequest request) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(adminUserService.approve(principal.userId(), id, request.note()));
    }

    @PostMapping("/{id}/disable")
    public R<UserSummary> disable(Authentication authentication, @PathVariable Long id, @RequestBody UserReviewRequest request) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(adminUserService.disable(principal.userId(), id, request.note()));
    }

    @PostMapping("/{id}/enable")
    public R<UserSummary> enable(Authentication authentication, @PathVariable Long id, @RequestBody UserReviewRequest request) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(adminUserService.enable(principal.userId(), id, request.note()));
    }

    @PutMapping("/{id}/settings")
    public R<UserSummary> updateSettings(Authentication authentication, @PathVariable Long id,
                                         @Valid @RequestBody UserSettingsUpdateRequest request) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(adminUserService.updateSettings(principal.userId(), id, request));
    }
}
