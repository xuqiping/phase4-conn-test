package com.superprogrammer.workreport.controller;

import com.superprogrammer.device.service.DeviceBindingService;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.workreport.service.WorkReportEventPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/client/work-report/events")
@RequiredArgsConstructor
public class WorkReportEventController {

    private final DeviceBindingService deviceBindingService;
    private final WorkReportEventPushService eventPushService;

    @GetMapping("/stream")
    public SseEmitter stream(
            Authentication auth,
            @RequestParam String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        deviceBindingService.requireActiveDevice(principal.userId(), deviceId);
        return eventPushService.subscribe(principal.userId());
    }
}
