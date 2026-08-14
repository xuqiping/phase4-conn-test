package com.superprogrammer.billing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.billing.dto.AvailablePricingModelVO;
import com.superprogrammer.billing.dto.PricingImportResult;
import com.superprogrammer.billing.dto.PricingRuleExportItem;
import com.superprogrammer.billing.dto.PricingRuleRequest;
import com.superprogrammer.billing.dto.PricingRuleVO;
import com.superprogrammer.billing.dto.RatioTierRequest;
import com.superprogrammer.billing.dto.RatioTierVO;
import com.superprogrammer.billing.service.PricingConfigService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * admin 价表/阶梯比例配置端点（权限 pricing:manage，仅 admin 默认有）。
 * <p>查询走 {@code GET}，建/改/删走 {@code POST/PUT/DELETE}。校验在 {@link PricingConfigService}。
 */
@Slf4j
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class PricingConfigController {

    private final PricingConfigService pricingConfigService;
    private final ObjectMapper objectMapper;

    // ---------------- 价表 ----------------

    @GetMapping("/pricing")
    @RequirePermission("pricing:manage")
    public ResponseEntity<R<List<PricingRuleVO>>> listPricingRules() {
        return ResponseEntity.ok(R.ok(pricingConfigService.listPricingRules()));
    }

    @GetMapping("/pricing/available-models")
    @RequirePermission("pricing:manage")
    public ResponseEntity<R<List<AvailablePricingModelVO>>> availablePricingModels() {
        return ResponseEntity.ok(R.ok(pricingConfigService.availablePricingModels()));
    }

    @AuditLog(module = "billing", action = "pricing_create", targetType = "pricing_rule")
    @PostMapping("/pricing")
    @RequirePermission("pricing:manage")
    public ResponseEntity<R<PricingRuleVO>> createPricingRule(@Valid @RequestBody PricingRuleRequest req) {
        return ResponseEntity.ok(R.ok("价表已创建", pricingConfigService.createPricingRule(req)));
    }

    @AuditLog(module = "billing", action = "pricing_update", targetType = "pricing_rule")
    @PutMapping("/pricing/{id}")
    @RequirePermission("pricing:manage")
    public ResponseEntity<R<PricingRuleVO>> updatePricingRule(@PathVariable Long id,
                                                              @Valid @RequestBody PricingRuleRequest req) {
        return ResponseEntity.ok(R.ok("价表已更新", pricingConfigService.updatePricingRule(id, req)));
    }

    /** 删除价表行（配错模型/价格的清理入口；历史账单金额已在扣费时落账，不受影响）。 */
    @AuditLog(module = "billing", action = "pricing_delete", targetType = "pricing_rule")
    @DeleteMapping("/pricing/{id}")
    @RequirePermission("pricing:manage")
    public ResponseEntity<R<Void>> deletePricingRule(@PathVariable Long id) {
        pricingConfigService.deletePricingRule(id);
        return ResponseEntity.ok(R.ok("价表已删除", null));
    }

    /**
     * 7x-2：导出当前全量价表为 JSON 文件（备份/迁移）。价表无加密，纯明文价格。
     */
    @GetMapping("/pricing/export")
    @RequirePermission("pricing:manage")
    @AuditLog(module = "billing", action = "pricing_export", targetType = "pricing_rule")
    public ResponseEntity<byte[]> exportPricingRules() {
        var items = pricingConfigService.exportAll();
        byte[] body;
        try {
            body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(items);
        } catch (Exception e) {
            log.error("导出价表序列化失败", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导出失败");
        }
        String filename = "pricing-rules-" + java.time.LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * 7x-2：下载「填充模板」——联动全局供应商，自动预填未配置过的模型（区分 LLM/图片/视频），
     * 用户填价格后上传。价表无密钥，无需二次确认。
     */
    @GetMapping("/pricing/template")
    @RequirePermission("pricing:manage")
    @AuditLog(module = "billing", action = "pricing_template_download", targetType = "pricing_rule")
    public ResponseEntity<byte[]> downloadPricingTemplate() {
        var items = pricingConfigService.generateTemplate();
        byte[] body;
        try {
            body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(items);
        } catch (Exception e) {
            log.error("生成价表模板序列化失败", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模板生成失败");
        }
        String filename = "pricing-template-" + java.time.LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * 7x-2：批量导入价表——按 (providerId, model, kind, hasReference) upsert，存在则覆盖价格。
     * 非法行不中断整体导入，返 created/updated/failed 统计。
     */
    @PostMapping("/pricing/import")
    @RequirePermission("pricing:manage")
    @AuditLog(module = "billing", action = "pricing_import", targetType = "pricing_rule")
    public ResponseEntity<R<PricingImportResult>> importPricingRules(
            @RequestBody List<PricingRuleExportItem> items) {
        return ResponseEntity.ok(R.ok(pricingConfigService.importAll(items)));
    }

    // ---------------- 阶梯比例 ----------------

    @GetMapping("/ratio")
    @RequirePermission("pricing:manage")
    public ResponseEntity<R<List<RatioTierVO>>> listRatioTiers() {
        return ResponseEntity.ok(R.ok(pricingConfigService.listRatioTiers()));
    }

    @AuditLog(module = "billing", action = "ratio_create", targetType = "ratio_tier")
    @PostMapping("/ratio")
    @RequirePermission("pricing:manage")
    public ResponseEntity<R<RatioTierVO>> createRatioTier(@Valid @RequestBody RatioTierRequest req) {
        return ResponseEntity.ok(R.ok("阶梯比例已创建", pricingConfigService.createRatioTier(req)));
    }

    @AuditLog(module = "billing", action = "ratio_update", targetType = "ratio_tier")
    @PutMapping("/ratio/{id}")
    @RequirePermission("pricing:manage")
    public ResponseEntity<R<RatioTierVO>> updateRatioTier(@PathVariable Long id,
                                                          @Valid @RequestBody RatioTierRequest req) {
        return ResponseEntity.ok(R.ok("阶梯比例已更新", pricingConfigService.updateRatioTier(id, req)));
    }

    @AuditLog(module = "billing", action = "ratio_delete", targetType = "ratio_tier")
    @DeleteMapping("/ratio/{id}")
    @RequirePermission("pricing:manage")
    public ResponseEntity<R<Void>> deleteRatioTier(@PathVariable Long id) {
        pricingConfigService.deleteRatioTier(id);
        return ResponseEntity.ok(R.ok("阶梯比例已删除", null));
    }
}
