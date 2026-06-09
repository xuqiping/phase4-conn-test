package com.superprogrammer.user.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.user.dto.ModuleEntitlementDto;
import com.superprogrammer.user.service.EntitlementService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/client/entitlements")
@RequiredArgsConstructor
public class ClientEntitlementController {

    private final EntitlementService entitlementService;

    @GetMapping
    public R<List<ModuleEntitlementDto>> list(Authentication authentication) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(entitlementService.listActiveByUserId(principal.userId()));
    }
}
