package com.superprogrammer.device.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.device.dto.AnonymousTrialStatusDto;
import com.superprogrammer.device.dto.SelectFreeModuleRequest;
import com.superprogrammer.device.dto.StartTrialRequest;
import com.superprogrammer.device.service.AnonymousTrialService;
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
    public R<AnonymousTrialStatusDto> start(@Valid @RequestBody StartTrialRequest request) {
        return R.ok(anonymousTrialService.start(request));
    }

    @GetMapping("/status")
    public R<AnonymousTrialStatusDto> status(@RequestParam String deviceId, @RequestParam String fingerprintHash) {
        return R.ok(anonymousTrialService.status(deviceId, fingerprintHash));
    }

    @PostMapping("/select-free-module")
    public R<AnonymousTrialStatusDto> selectFreeModule(@Valid @RequestBody SelectFreeModuleRequest request) {
        return R.ok(anonymousTrialService.selectFreeModule(request));
    }

    @PostMapping("/change-free-module")
    public R<AnonymousTrialStatusDto> changeFreeModule(@Valid @RequestBody SelectFreeModuleRequest request) {
        return R.ok(anonymousTrialService.changeFreeModule(request));
    }
}
