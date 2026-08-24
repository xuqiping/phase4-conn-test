package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.device.service.DeviceBindingService;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.workreport.dto.CreateFixedWorkItemRequest;
import com.superprogrammer.workreport.dto.FixedWorkItemDto;
import com.superprogrammer.workreport.dto.UpdateFixedWorkItemRequest;
import com.superprogrammer.workreport.service.FixedWorkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/client/work-report/fixed-work")
@RequiredArgsConstructor
public class FixedWorkController {

    private final DeviceBindingService deviceBindingService;
    private final FixedWorkService fixedWorkService;

    private AuthPrincipal requireActiveDevice(Authentication auth, String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        deviceBindingService.requireActiveDevice(principal.userId(), deviceId);
        return principal;
    }

    @GetMapping
    public R<List<FixedWorkItemDto>> list(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestParam String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        if (date != null) {
            return R.ok(fixedWorkService.listByUserAndDate(principal.userId(), date));
        }
        return R.ok(fixedWorkService.listByUserAndType(principal.userId(), type));
    }

    @PostMapping("/{id}/toggle-complete")
    public R<FixedWorkItemDto> toggleComplete(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(fixedWorkService.toggleComplete(principal.userId(), id, date));
    }

    @PostMapping
    public R<FixedWorkItemDto> create(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestBody @Valid CreateFixedWorkItemRequest request) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(fixedWorkService.create(principal.userId(), request));
    }

    @PutMapping("/{id}")
    public R<FixedWorkItemDto> update(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id,
            @RequestBody @Valid UpdateFixedWorkItemRequest request) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(fixedWorkService.update(principal.userId(), id, request));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        fixedWorkService.delete(principal.userId(), id);
        return R.ok();
    }
}
