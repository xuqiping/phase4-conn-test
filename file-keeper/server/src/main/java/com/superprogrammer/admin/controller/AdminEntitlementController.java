package com.superprogrammer.admin.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.user.dto.GrantEntitlementRequest;
import com.superprogrammer.user.dto.ModuleEntitlementDto;
import com.superprogrammer.user.dto.UpdateEntitlementRequest;
import com.superprogrammer.user.service.EntitlementService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users/{userId}/entitlements")
@RequiredArgsConstructor
public class AdminEntitlementController {

    private final EntitlementService entitlementService;

    @GetMapping
    public R<List<ModuleEntitlementDto>> list(@PathVariable Long userId) {
        return R.ok(entitlementService.listByUserId(userId));
    }

    @PostMapping
    public R<ModuleEntitlementDto> grant(Authentication authentication, @PathVariable Long userId,
                                          @Valid @RequestBody GrantEntitlementRequest request) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(entitlementService.grant(principal.userId(), userId, request));
    }

    @PutMapping("/{entitlementId}")
    public R<ModuleEntitlementDto> update(Authentication authentication, @PathVariable Long userId,
                                           @PathVariable Long entitlementId,
                                           @RequestBody UpdateEntitlementRequest request) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(entitlementService.update(principal.userId(), userId, entitlementId, request));
    }

    @DeleteMapping("/{entitlementId}")
    public R<Void> revoke(Authentication authentication, @PathVariable Long userId,
                           @PathVariable Long entitlementId) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        entitlementService.revoke(principal.userId(), userId, entitlementId);
        return R.ok();
    }
}
