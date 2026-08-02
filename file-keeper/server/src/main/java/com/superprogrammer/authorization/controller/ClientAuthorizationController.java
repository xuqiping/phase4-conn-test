package com.superprogrammer.authorization.controller;

import com.superprogrammer.authorization.dto.AuthorizationSnapshot;
import com.superprogrammer.authorization.service.AuthorizationService;
import com.superprogrammer.common.R;
import com.superprogrammer.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/client")
@RequiredArgsConstructor
public class ClientAuthorizationController {

    private final AuthorizationService authorizationService;

    @GetMapping("/authorization")
    public R<AuthorizationSnapshot> authorization(Authentication authentication,
                                                  @RequestParam String deviceId,
                                                  @RequestParam(required = false) Long clientTimestamp) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return R.ok(authorizationService.authenticatedSnapshot(principal.userId(), deviceId, clientTimestamp));
    }
}
