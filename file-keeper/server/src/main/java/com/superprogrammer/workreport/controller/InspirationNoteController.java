package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.device.service.DeviceBindingService;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.workreport.dto.CreateInspirationNoteRequest;
import com.superprogrammer.workreport.dto.InspirationNoteDto;
import com.superprogrammer.workreport.service.InspirationNoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/client/work-report/inspirations")
@RequiredArgsConstructor
public class InspirationNoteController {

    private final DeviceBindingService deviceBindingService;
    private final InspirationNoteService inspirationNoteService;

    private AuthPrincipal requireActiveDevice(Authentication auth, String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        deviceBindingService.requireActiveDevice(principal.userId(), deviceId);
        return principal;
    }

    @GetMapping
    public R<List<InspirationNoteDto>> list(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(inspirationNoteService.listByUser(principal.userId(), tags, startDate, endDate));
    }

    @PostMapping
    public R<InspirationNoteDto> create(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestBody @Valid CreateInspirationNoteRequest request) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(inspirationNoteService.create(principal.userId(), request));
    }

    @PutMapping("/{id}")
    public R<InspirationNoteDto> update(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id,
            @RequestBody @Valid CreateInspirationNoteRequest request) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(inspirationNoteService.update(principal.userId(), id, request));
    }

    @PostMapping("/{id}/review")
    public R<InspirationNoteDto> review(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(inspirationNoteService.markReviewed(principal.userId(), id));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        inspirationNoteService.delete(principal.userId(), id);
        return R.ok();
    }
}
