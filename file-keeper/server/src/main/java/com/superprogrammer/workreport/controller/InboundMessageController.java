package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.R;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.authorization.dto.AuthorizationSnapshot;
import com.superprogrammer.authorization.service.AuthorizationService;
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

    private final AuthorizationService authorizationService;
    private final InboundMessageService inboundMessageService;

    private boolean checkModuleAuth(Authentication auth, String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        AuthorizationSnapshot snapshot = authorizationService.authenticatedSnapshot(
                principal.userId(), deviceId, System.currentTimeMillis()
        );
        return snapshot.modules().stream()
                .anyMatch(m -> AuthConstants.MODULE_WORK_REPORT.equals(m.moduleCode()) && m.allowed());
    }

    @GetMapping
    public R<List<InboundMessageDto>> listPending(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "50") @Max(200) int limit) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(inboundMessageService.listPending(principal.userId(), limit));
    }

    @PostMapping("/{id}/confirm")
    public R<InboundMessageDto> confirm(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id,
            @RequestBody @Valid ConfirmInboundMessageRequest request) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(inboundMessageService.confirm(principal.userId(), id, request.action(), request.correctedPayload()));
    }
}
