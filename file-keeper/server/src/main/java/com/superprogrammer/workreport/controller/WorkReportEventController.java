package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.authorization.dto.AuthorizationSnapshot;
import com.superprogrammer.authorization.service.AuthorizationService;
import com.superprogrammer.workreport.service.WorkReportEventPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/client/work-report/events")
@RequiredArgsConstructor
public class WorkReportEventController {

    private final AuthorizationService authorizationService;
    private final WorkReportEventPushService eventPushService;

    @GetMapping("/stream")
    public SseEmitter stream(
            Authentication auth,
            @RequestParam String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        AuthorizationSnapshot snapshot = authorizationService.authenticatedSnapshot(
                principal.userId(), deviceId, System.currentTimeMillis()
        );
        boolean allowed = snapshot.modules().stream()
                .anyMatch(m -> AuthConstants.MODULE_WORK_REPORT.equals(m.moduleCode()) && m.allowed());
        if (!allowed) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "未授权访问工作汇报模块");
        }
        return eventPushService.subscribe(principal.userId());
    }
}
