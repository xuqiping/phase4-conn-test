package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.device.service.DeviceBindingService;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.workreport.dto.ConfirmInboundMessageRequest;
import com.superprogrammer.workreport.dto.InboundMessageDto;
import com.superprogrammer.workreport.service.InboundMessageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/client/work-report/inbox")
@RequiredArgsConstructor
public class InboundMessageController {

    private final DeviceBindingService deviceBindingService;
    private final InboundMessageService inboundMessageService;

    private AuthPrincipal requireActiveDevice(Authentication auth, String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        deviceBindingService.requireActiveDevice(principal.userId(), deviceId);
        return principal;
    }

    @GetMapping
    public R<List<InboundMessageDto>> listPending(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "50") @Max(200) int limit) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(inboundMessageService.listPending(principal.userId(), limit));
    }

    @PostMapping("/{id}/confirm")
    public R<InboundMessageDto> confirm(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id,
            @RequestBody @Valid ConfirmInboundMessageRequest request) {
        AuthPrincipal principal = requireActiveDevice(auth, deviceId);
        return R.ok(inboundMessageService.confirm(principal.userId(), id, request.action(), request.correctedPayload()));
    }
}
