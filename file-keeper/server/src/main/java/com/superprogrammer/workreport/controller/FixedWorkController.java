package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.R;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.authorization.dto.AuthorizationSnapshot;
import com.superprogrammer.authorization.service.AuthorizationService;
import com.superprogrammer.workreport.dto.CreateFixedWorkItemRequest;
import com.superprogrammer.workreport.dto.FixedWorkItemDto;
import com.superprogrammer.workreport.dto.UpdateFixedWorkItemRequest;
import com.superprogrammer.workreport.service.FixedWorkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/client/work-report/fixed-work")
@RequiredArgsConstructor
public class FixedWorkController {

    private final AuthorizationService authorizationService;
    private final FixedWorkService fixedWorkService;

    private boolean checkModuleAuth(Authentication auth, String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        AuthorizationSnapshot snapshot = authorizationService.authenticatedSnapshot(
                principal.userId(), deviceId, System.currentTimeMillis()
        );
        return snapshot.modules().stream()
                .anyMatch(m -> AuthConstants.MODULE_WORK_REPORT.equals(m.moduleCode()) && m.allowed());
    }

    @GetMapping
    public R<List<FixedWorkItemDto>> list(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestParam String type) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(fixedWorkService.listByUserAndType(principal.userId(), type));
    }

    @PostMapping
    public R<FixedWorkItemDto> create(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestBody @Valid CreateFixedWorkItemRequest request) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(fixedWorkService.create(principal.userId(), request));
    }

    @PutMapping("/{id}")
    public R<FixedWorkItemDto> update(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id,
            @RequestBody @Valid UpdateFixedWorkItemRequest request) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(fixedWorkService.update(principal.userId(), id, request));
    }

    @PostMapping("/{id}/toggle-complete")
    public R<FixedWorkItemDto> toggleComplete(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(fixedWorkService.toggleComplete(principal.userId(), id));
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
        fixedWorkService.delete(principal.userId(), id);
        return R.ok();
    }
}
