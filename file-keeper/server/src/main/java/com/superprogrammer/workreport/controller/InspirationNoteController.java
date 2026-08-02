package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.R;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.authorization.dto.AuthorizationSnapshot;
import com.superprogrammer.authorization.service.AuthorizationService;
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

    private final AuthorizationService authorizationService;
    private final InspirationNoteService inspirationNoteService;

    private boolean checkModuleAuth(Authentication auth, String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        AuthorizationSnapshot snapshot = authorizationService.authenticatedSnapshot(
                principal.userId(), deviceId, System.currentTimeMillis()
        );
        return snapshot.modules().stream()
                .anyMatch(m -> AuthConstants.MODULE_WORK_REPORT.equals(m.moduleCode()) && m.allowed());
    }

    @GetMapping
    public R<List<InspirationNoteDto>> list(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(inspirationNoteService.listByUser(principal.userId(), tags, startDate, endDate));
    }

    @PostMapping
    public R<InspirationNoteDto> create(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestBody @Valid CreateInspirationNoteRequest request) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(inspirationNoteService.create(principal.userId(), request));
    }

    @PutMapping("/{id}")
    public R<InspirationNoteDto> update(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id,
            @RequestBody @Valid CreateInspirationNoteRequest request) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(inspirationNoteService.update(principal.userId(), id, request));
    }

    @PostMapping("/{id}/review")
    public R<InspirationNoteDto> review(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(inspirationNoteService.markReviewed(principal.userId(), id));
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
        inspirationNoteService.delete(principal.userId(), id);
        return R.ok();
    }
}
