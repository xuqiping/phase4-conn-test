package com.superprogrammer.authorization.controller;

import com.superprogrammer.authorization.dto.AnonymousAuthorizationSnapshot;
import com.superprogrammer.authorization.service.AuthorizationService;
import com.superprogrammer.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/anonymous")
@RequiredArgsConstructor
public class AnonymousAuthorizationController {

    private final AuthorizationService authorizationService;

    @GetMapping("/authorization")
    public R<AnonymousAuthorizationSnapshot> authorization(@RequestParam String deviceId, @RequestParam String fingerprintHash) {
        return R.ok(authorizationService.anonymousSnapshot(deviceId, fingerprintHash));
    }
}
