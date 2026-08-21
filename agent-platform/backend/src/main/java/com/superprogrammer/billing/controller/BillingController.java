package com.superprogrammer.billing.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.billing.dto.DailyTrendVO;
import com.superprogrammer.billing.dto.ProjectGroupOptionVO;
import com.superprogrammer.billing.dto.ReconcileDiffVO;
import com.superprogrammer.billing.dto.UsageDetailVO;
import com.superprogrammer.billing.dto.UsageDimensionVO;
import com.superprogrammer.billing.dto.UsageOverviewVO;
import com.superprogrammer.billing.dto.UserUsageVO;
import com.superprogrammer.billing.dto.UserWalletVO;
import com.superprogrammer.billing.service.BillingQueryService;
import com.superprogrammer.billing.service.BillingReconcileService;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 账单/钱包查询（Chunk I Step16）。
 *
 * <p>分权两段：
 * <ul>
 *   <li><b>admin</b>（{@code /api/billing/admin/*}）：{@code @RequirePermission("usage:view")}，见真 token/¥/积分 +
 *       用户/模型/kind 排行 + 日趋势。</li>
 *   <li><b>user</b>（{@code /api/billing/me/*}）：ownership 强制——userId 取自 SecurityContext（不接外部入参），
 *       VO 刻意不含 token/¥（spec §3 用户侧不暴露）。</li>
 * </ul>
 * <p>日期 from/to 均 optional（ISO_OFFSET_DATE_TIME）；service 层兜底默认窗 30 天 + 超 365 天 clamp。
 */
@Slf4j
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingQueryService queryService;
    private final BillingReconcileService reconcileService;

    // ---------- admin（usage:view） ----------

    @GetMapping("/admin/overview")
    @RequirePermission("usage:view")
    public ResponseEntity<R<UsageOverviewVO>> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ResponseEntity.ok(R.ok(queryService.overview(from, to)));
    }

    @GetMapping("/admin/by-user")
    @RequirePermission("usage:view")
    public ResponseEntity<R<List<UsageDimensionVO>>> byUser(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(R.ok(queryService.rankByUser(from, to, limit)));
    }

    @GetMapping("/admin/by-model")
    @RequirePermission("usage:view")
    public ResponseEntity<R<List<UsageDimensionVO>>> byModel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(R.ok(queryService.rankByModel(from, to, limit)));
    }

    @GetMapping("/admin/by-kind")
    @RequirePermission("usage:view")
    public ResponseEntity<R<List<UsageDimensionVO>>> byKind(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ResponseEntity.ok(R.ok(queryService.rankByKind(from, to)));
    }

    @GetMapping("/admin/trend")
    @RequirePermission("usage:view")
    public ResponseEntity<R<List<DailyTrendVO>>> trend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ResponseEntity.ok(R.ok(queryService.dailyTrend(from, to)));
    }

    /**
     * admin 调用明细（逐条 llm_usage_logs，含 token/¥/积分 + username）。
     * <p>分页 + 按 用户/模型/类型/状态 筛选 + 日期区间。镜像 RagRetrievalLog 的分页范式。
     */
    @GetMapping("/admin/call-log")
    @RequirePermission("usage:view")
    public ResponseEntity<R<PageResult<UsageDetailVO>>> callLog(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Long projectGroupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ResponseEntity.ok(R.ok(queryService.pageDetail(
                from, to, userId, model, kind, status, traceId, taskId, projectGroupId, page, size)));
    }

    /**
     * 计划5 Step8：账单页「项目组」筛选下拉数据源（id+name）。
     * 与 call-log 同权限（usage:view），只读轻量。
     */
    @GetMapping("/admin/project-group-options")
    @RequirePermission("usage:view")
    public ResponseEntity<R<List<ProjectGroupOptionVO>>> projectGroupOptions() {
        return ResponseEntity.ok(R.ok(queryService.projectGroupOptions()));
    }

    /**
     * admin 计费对账（安全体系 S1 · SEC-FR-123）：手动触发一次「余额 vs Σ流水」全量对账。
     * <p>返回差异行（空=全平）；差异行同时写安全审计 + ERROR 日志（与每日定时任务同路径）。
     * 只读，不自动修账。
     */
    @GetMapping("/admin/reconcile")
    @RequirePermission("usage:view")
    public ResponseEntity<R<List<ReconcileDiffVO>>> reconcile() {
        return ResponseEntity.ok(R.ok(reconcileService.reconcile()));
    }

    /**
     * 支付渠道异常（7x#3 对账扩展）：PENDING 超 10min 未关 / PAID 无流水 / 终态后仍付款 三节。
     * 只读不自动修——人工补单线索（mock 通道可精确复现「已付已关单」时序验证本列表）。
     */
    @GetMapping("/admin/reconcile/payment-anomalies")
    @RequirePermission("usage:view")
    public ResponseEntity<R<com.superprogrammer.billing.dto.PaymentAnomalyVO>> paymentAnomalies() {
        return ResponseEntity.ok(R.ok(reconcileService.paymentAnomalies()));
    }

    // ---------- user（ownership = current userId，无外部旁路） ----------

    @GetMapping("/me/wallet")
    public ResponseEntity<R<UserWalletVO>> myWallet() {
        return ResponseEntity.ok(R.ok(queryService.userWallet(currentUserId())));
    }

    @GetMapping("/me/usage")
    public ResponseEntity<R<List<UserUsageVO>>> myUsage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Long projectGroupId) {
        return ResponseEntity.ok(R.ok(queryService.userUsage(currentUserId(), from, to, projectGroupId)));
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getPrincipal() == null ? null : (Long) auth.getPrincipal();
    }
}
