package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.PageResult;
import com.superprogrammer.common.R;
import com.superprogrammer.authorization.dto.AuthorizationSnapshot;
import com.superprogrammer.authorization.dto.ModuleAccess;
import com.superprogrammer.authorization.service.AuthorizationService;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.workreport.dto.CreateWorkLogRequest;
import com.superprogrammer.workreport.dto.GenerateReportRequest;
import com.superprogrammer.workreport.dto.PushCredentialCreateRequest;
import com.superprogrammer.workreport.dto.PushCredentialDto;
import com.superprogrammer.workreport.dto.PushCredentialUpdateRequest;
import com.superprogrammer.workreport.dto.PushTargetCreateRequest;
import com.superprogrammer.workreport.dto.PushTargetDto;
import com.superprogrammer.workreport.dto.PushTargetUpdateRequest;
import com.superprogrammer.workreport.dto.ReportConfigDto;
import com.superprogrammer.workreport.dto.ReportTemplateDto;
import com.superprogrammer.workreport.dto.SaveReportConfigRequest;
import com.superprogrammer.workreport.dto.UpdateWorkLogRequest;
import com.superprogrammer.workreport.dto.WorkLogDto;
import com.superprogrammer.workreport.dto.WorkReportDto;
import com.superprogrammer.workreport.service.ReportConfigService;
import com.superprogrammer.workreport.service.ReportPushService;
import com.superprogrammer.workreport.service.ReportTemplateService;
import com.superprogrammer.workreport.service.WorkLogService;
import com.superprogrammer.workreport.service.WorkReportService;
import com.superprogrammer.workreport.service.PushCredentialService;
import com.superprogrammer.workreport.service.PushTargetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/client/work-report")
@RequiredArgsConstructor
@Slf4j
public class WorkReportClientController {

    private final AuthorizationService authorizationService;
    private final WorkLogService workLogService;
    private final ReportTemplateService reportTemplateService;
    private final ReportConfigService reportConfigService;
    private final WorkReportService workReportService;
    private final ReportPushService reportPushService;
    private final PushCredentialService pushCredentialService;
    private final PushTargetService pushTargetService;

    private ModuleAccess checkModuleAuth(Authentication auth, String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        AuthorizationSnapshot snapshot = authorizationService.authenticatedSnapshot(
                principal.userId(), deviceId, System.currentTimeMillis()
        );
        return snapshot.modules().stream()
                .filter(m -> AuthConstants.MODULE_WORK_REPORT.equals(m.moduleCode()))
                .findFirst()
                .orElse(null);
    }

    private <T> R<T> forbidden(ModuleAccess access) {
        String reason = access != null && access.reason() != null ? access.reason() : "未授权访问工作汇报模块";
        return R.fail(ErrorCode.FORBIDDEN.getCode(), reason);
    }

    // ==================== 工作记录 ====================
    @GetMapping("/logs")
    public R<List<WorkLogDto>> listLogs(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        if (startDate == null && endDate == null) {
            LocalDate today = LocalDate.now();
            return R.ok(workLogService.listByUserAndDate(principal.userId(), today));
        }
        LocalDate effectiveStart = startDate == null ? LocalDate.now() : startDate;
        LocalDate effectiveEnd = endDate == null ? effectiveStart : endDate;
        if (effectiveEnd.isBefore(effectiveStart)) {
            return R.fail(ErrorCode.UNPROCESSABLE.getCode(), "结束日期不能早于开始日期");
        }
        return R.ok(workLogService.listByUserAndDateRange(principal.userId(), effectiveStart, effectiveEnd));
    }

    @PostMapping("/logs")
    public R<WorkLogDto> createLog(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestBody @Valid CreateWorkLogRequest request) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(workLogService.create(principal.userId(), request));
    }

    @PutMapping("/logs/{id}")
    public R<WorkLogDto> updateLog(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id,
            @RequestBody @Valid UpdateWorkLogRequest request) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(workLogService.update(principal.userId(), id, request));
    }

    @DeleteMapping("/logs/{id}")
    public R<Void> deleteLog(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        workLogService.delete(principal.userId(), id);
        return R.ok();
    }

    // ==================== 报告模板 ====================
    @GetMapping("/templates")
    public R<List<ReportTemplateDto>> listTemplates(
            Authentication auth,
            @RequestParam String deviceId) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(reportTemplateService.listAvailable(principal.userId()));
    }

    // ==================== 报告配置 ====================
    @GetMapping("/configs")
    public R<List<ReportConfigDto>> listConfigs(
            Authentication auth,
            @RequestParam String deviceId) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(reportConfigService.listByUser(principal.userId()));
    }

    @GetMapping("/configs/{id}")
    public R<ReportConfigDto> getConfig(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(reportConfigService.getById(principal.userId(), id));
    }

    @PostMapping("/configs")
    public R<ReportConfigDto> saveConfig(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestBody @Valid SaveReportConfigRequest request) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(reportConfigService.save(principal.userId(), request));
    }

    @DeleteMapping("/configs/{id}")
    public R<Void> deleteConfig(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        reportConfigService.delete(principal.userId(), id);
        return R.ok();
    }

    // ==================== 推送配置（凭据 + 目标） ====================
    @GetMapping("/push-credentials")
    public R<List<PushCredentialDto>> listPushCredentials(
            Authentication auth,
            @RequestParam String deviceId) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(pushCredentialService.listByUser(principal.userId()));
    }

    @PostMapping("/push-credentials")
    public R<PushCredentialDto> createPushCredential(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestBody @Valid PushCredentialCreateRequest request) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(pushCredentialService.create(principal.userId(), request));
    }

    @PutMapping("/push-credentials/{id}")
    public R<PushCredentialDto> updatePushCredential(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id,
            @RequestBody @Valid PushCredentialUpdateRequest request) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(pushCredentialService.update(principal.userId(), id, request));
    }

    @DeleteMapping("/push-credentials/{id}")
    public R<Void> deletePushCredential(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        pushCredentialService.delete(principal.userId(), id);
        return R.ok();
    }

    @GetMapping("/push-targets")
    public R<List<PushTargetDto>> listPushTargets(
            Authentication auth,
            @RequestParam String deviceId) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(pushTargetService.listByUser(principal.userId()));
    }

    @PostMapping("/push-targets")
    public R<PushTargetDto> createPushTarget(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestBody @Valid PushTargetCreateRequest request) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(pushTargetService.create(principal.userId(), request));
    }

    @PutMapping("/push-targets/{id}")
    public R<PushTargetDto> updatePushTarget(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id,
            @RequestBody @Valid PushTargetUpdateRequest request) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(pushTargetService.update(principal.userId(), id, request));
    }

    @DeleteMapping("/push-targets/{id}")
    public R<Void> deletePushTarget(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        pushTargetService.delete(principal.userId(), id);
        return R.ok();
    }

    // ==================== 报告实例 ====================
    @PostMapping("/reports/generate")
    public R<WorkReportDto> generateReport(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestBody @Valid GenerateReportRequest request) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(workReportService.generate(principal.userId(), request.configId()));
    }

    @PostMapping("/reports/{id}/push")
    public R<Void> pushReport(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        log.info("[pushReport] 收到推送请求 reportId={} deviceId={}", id, deviceId);
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        WorkReportDto report = workReportService.getById(principal.userId(), id);
        log.info("[pushReport] 准备异步推送 reportId={}", id);
        reportPushService.pushReport(report.id());
        return R.ok();
    }

    @GetMapping("/reports")
    public R<PageResult<WorkReportDto>> listReports(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(workReportService.pageByUser(principal.userId(), page, size));
    }

    @GetMapping("/reports/{id}")
    public R<WorkReportDto> getReport(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(workReportService.getById(principal.userId(), id));
    }

    @DeleteMapping("/reports/{id}")
    public R<Void> deleteReport(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id) {
        ModuleAccess access = checkModuleAuth(auth, deviceId);
        if (access == null || !access.allowed()) {
            return forbidden(access);
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        workReportService.delete(principal.userId(), id);
        return R.ok();
    }
}
