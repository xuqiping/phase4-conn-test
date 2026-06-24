package com.superprogrammer.device.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.device.dto.AnonymousTrialStatusDto;
import com.superprogrammer.device.dto.SelectFreeModuleRequest;
import com.superprogrammer.device.dto.StartTrialRequest;
import com.superprogrammer.device.service.AnonymousTrialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/anonymous/trial")
@RequiredArgsConstructor
public class AnonymousTrialController {

    private final AnonymousTrialService anonymousTrialService;

    @PostMapping("/start")
    public R<AnonymousTrialStatusDto> start(@Valid @RequestBody StartTrialRequest request, HttpServletRequest httpRequest) {
        return R.ok(anonymousTrialService.start(request, clientIp(httpRequest), httpRequest.getHeader("User-Agent")));
    }

    @GetMapping("/status")
    public R<AnonymousTrialStatusDto> status(@RequestParam String deviceId, @RequestParam String fingerprintHash, HttpServletRequest httpRequest) {
        return R.ok(anonymousTrialService.status(deviceId, fingerprintHash, clientIp(httpRequest), httpRequest.getHeader("User-Agent")));
    }

    @PostMapping("/select-free-module")
    public R<AnonymousTrialStatusDto> selectFreeModule(@Valid @RequestBody SelectFreeModuleRequest request, HttpServletRequest httpRequest) {
        return R.ok(anonymousTrialService.selectFreeModule(request, clientIp(httpRequest), httpRequest.getHeader("User-Agent")));
    }

    @PostMapping("/change-free-module")
    public R<AnonymousTrialStatusDto> changeFreeModule(@Valid @RequestBody SelectFreeModuleRequest request, HttpServletRequest httpRequest) {
        return R.ok(anonymousTrialService.changeFreeModule(request, clientIp(httpRequest), httpRequest.getHeader("User-Agent")));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
