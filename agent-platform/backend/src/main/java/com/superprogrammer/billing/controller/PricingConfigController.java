package com.superprogrammer.billing.controller;

import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.billing.dto.AvailablePricingModelVO;
import com.superprogrammer.billing.dto.PricingRuleRequest;
import com.superprogrammer.billing.dto.PricingRuleVO;
import com.superprogrammer.billing.dto.RatioTierRequest;
import com.superprogrammer.billing.dto.RatioTierVO;
import com.superprogrammer.billing.service.PricingConfigService;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
