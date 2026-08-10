package com.superprogrammer.common.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 审计日志查询 API（日志系统 LOG-FR-12）：管理员日志中心数据源。
 * 筛选：用户/模块/动作/结果/时间段；分页走 created_at DESC（idx_audit_created 索引覆盖，禁全表扫）。
 * 权限：{@code system:audit:read}（V79 seed，仅 admin 默认持有；无权限 403）。
 */
@RestController
@RequestMapping("/api/audit/logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogMapper auditLogMapper;
    private final AuditChainVerifyService chainVerifyService;

    @GetMapping
    @RequirePermission("system:audit:read")
    public R<PageResult<AuditLogVO>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // size 上限 100：防拉全表（性能清单：禁全表扫）
        int safeSize = Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<AuditLogEntity> wrapper = new LambdaQueryWrapper<AuditLogEntity>()
                .eq(userId != null, AuditLogEntity::getUserId, userId)
                .eq(module != null && !module.isBlank(), AuditLogEntity::getModule, module)
                .eq(action != null && !action.isBlank(), AuditLogEntity::getAction, action)
                .eq(result != null && !result.isBlank(), AuditLogEntity::getResult, result)
                .eq(traceId != null && !traceId.isBlank(), AuditLogEntity::getTraceId, traceId)
                .ge(startTime != null, AuditLogEntity::getCreatedAt, startTime)
                .le(endTime != null, AuditLogEntity::getCreatedAt, endTime)
                .orderByDesc(AuditLogEntity::getCreatedAt)
                .orderByDesc(AuditLogEntity::getId);

        Page<AuditLogEntity> entityPage = auditLogMapper.selectPage(new Page<>(page, safeSize), wrapper);
        List<AuditLogVO> vos = entityPage.getRecords().stream().map(AuditLogVO::from).toList();
        return R.ok(PageResult.of(vos, entityPage.getTotal(), page, safeSize));
    }

    /**
     * 安全体系 S2 D3（SEC-FR-042）：手动触发审计链全量校验（每日 03:40 定时之外的即时入口）。
     * 通过则顺带完成 D4 锚定；断链返 ok=false + 首个断点行号（同时已写安全审计+ERROR 日志）。
     */
    @GetMapping("/verify-chain")
    @RequirePermission("system:audit:read")
    public R<AuditChainVerifyService.ChainVerifyResult> verifyChain() {
        return R.ok(chainVerifyService.verifyAndAnchor());
    }
}
