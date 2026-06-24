package com.superprogrammer.workreport.controller.admin;

import com.superprogrammer.common.R;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.audit.service.AdminAuditLogService;
import com.superprogrammer.workreport.dto.ReportTemplateDto;
import com.superprogrammer.workreport.dto.SaveReportTemplateRequest;
import com.superprogrammer.workreport.service.ReportTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/work-report")
@RequiredArgsConstructor
public class AdminWorkReportController {

    private final ReportTemplateService reportTemplateService;
    private final AdminAuditLogService adminAuditLogService;

    @GetMapping("/templates")
    public R<List<ReportTemplateDto>> listSystemTemplates() {
        return R.ok(reportTemplateService.listSystemTemplates());
    }

    @PostMapping("/templates")
    public R<ReportTemplateDto> createSystemTemplate(
            Authentication authentication,
            @RequestBody @Valid SaveReportTemplateRequest request) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        ReportTemplateDto dto = reportTemplateService.createSystemTemplate(request);
        adminAuditLogService.record(principal.userId(), "CREATE_SYSTEM_REPORT_TEMPLATE", "report_template",
                String.valueOf(dto.id()), "创建系统模板: " + request.name());
        return R.ok(dto);
    }

    @PutMapping("/templates/{id}")
    public R<ReportTemplateDto> updateSystemTemplate(
            Authentication authentication,
            @PathVariable Long id,
            @RequestBody @Valid SaveReportTemplateRequest request) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        ReportTemplateDto dto = reportTemplateService.updateSystemTemplate(id, request);
        adminAuditLogService.record(principal.userId(), "UPDATE_SYSTEM_REPORT_TEMPLATE", "report_template",
                String.valueOf(id), "更新系统模板: " + request.name());
        return R.ok(dto);
    }

    @DeleteMapping("/templates/{id}")
    public R<Void> deleteSystemTemplate(
            Authentication authentication,
            @PathVariable Long id) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        reportTemplateService.deleteSystemTemplate(id);
        adminAuditLogService.record(principal.userId(), "DELETE_SYSTEM_REPORT_TEMPLATE", "report_template",
                String.valueOf(id), "删除系统模板");
        return R.ok();
    }
}
