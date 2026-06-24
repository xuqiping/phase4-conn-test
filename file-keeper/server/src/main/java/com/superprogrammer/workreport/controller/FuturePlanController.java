package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.R;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.authorization.dto.AuthorizationSnapshot;
import com.superprogrammer.authorization.service.AuthorizationService;
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

    private final AuthorizationService authorizationService;
    private final FuturePlanService futurePlanService;

    private boolean checkModuleAuth(Authentication auth, String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        AuthorizationSnapshot snapshot = authorizationService.authenticatedSnapshot(
                principal.userId(), deviceId, System.currentTimeMillis()
        );
        return snapshot.modules().stream()
                .anyMatch(m -> AuthConstants.MODULE_WORK_REPORT.equals(m.moduleCode()) && m.allowed());
    }

    @GetMapping
    public R<List<FuturePlanDto>> list(
            Authentication auth,
            @RequestParam String deviceId) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(futurePlanService.listByUser(principal.userId()));
    }

    @PostMapping
    public R<FuturePlanDto> create(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestBody @Valid CreateFuturePlanRequest request) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(futurePlanService.create(principal.userId(), request));
    }

    @PutMapping("/{id}")
    public R<FuturePlanDto> update(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id,
            @RequestBody @Valid UpdateFuturePlanRequest request) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(futurePlanService.update(principal.userId(), id, request));
    }

    @PostMapping("/{id}/complete")
    public R<FuturePlanDto> complete(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(futurePlanService.complete(principal.userId(), id));
    }

    @PostMapping("/{id}/cancel")
    public R<FuturePlanDto> cancel(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(futurePlanService.cancel(principal.userId(), id));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        futurePlanService.delete(principal.userId(), id);
        return R.ok();
    }
}
