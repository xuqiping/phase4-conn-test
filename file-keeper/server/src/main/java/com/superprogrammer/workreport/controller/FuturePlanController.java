package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.device.service.DeviceBindingService;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.workreport.dto.CreateFuturePlanRequest;
import com.superprogrammer.workreport.dto.FuturePlanDto;
import com.superprogrammer.workreport.dto.UpdateFuturePlanRequest;
import com.superprogrammer.workreport.service.FuturePlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/client/work-report/future-plans")
@RequiredArgsConstructor
public class FuturePlanController {

    private final DeviceBindingService deviceBindingService;
    private final FuturePlanService futurePlanService;

    private AuthPrincipal requireActiveDevice(Authentication auth, String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        deviceBindingService.requireActiveDevice(principal.userId(), deviceId);
        return principal;
    }

    @GetMapping
    public R<List<FuturePlanDto>> list(
            Authentication auth,
            @RequestParam String deviceId) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(futurePlanService.listByUser(principal.userId()));
    }

    @PostMapping
    public R<FuturePlanDto> create(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestBody @Valid CreateFuturePlanRequest request) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(futurePlanService.create(principal.userId(), request));
    }

    @PutMapping("/{id}")
    public R<FuturePlanDto> update(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id,
            @RequestBody @Valid UpdateFuturePlanRequest request) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(futurePlanService.update(principal.userId(), id, request));
    }

    @PostMapping("/{id}/complete")
    public R<FuturePlanDto> complete(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(futurePlanService.complete(principal.userId(), id));
    }

    @PostMapping("/{id}/cancel")
    public R<FuturePlanDto> cancel(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(futurePlanService.cancel(principal.userId(), id));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        futurePlanService.delete(principal.userId(), id);
        return R.ok();
    }
}
