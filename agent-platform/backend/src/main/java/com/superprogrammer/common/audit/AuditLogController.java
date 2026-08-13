package com.superprogrammer.common.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final UserMapper userMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @GetMapping
    @RequirePermission("system:audit:read")
    public R<PageResult<AuditLogVO>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String username,
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
        // username 模糊匹配（参数化防注入；idx_audit_username 覆盖前缀，admin 低频双侧模糊可接受）
        LambdaQueryWrapper<AuditLogEntity> wrapper = new LambdaQueryWrapper<AuditLogEntity>()
                .eq(userId != null, AuditLogEntity::getUserId, userId)
                .like(username != null && !username.isBlank(), AuditLogEntity::getUsername, username)
                .eq(module != null && !module.isBlank(), AuditLogEntity::getModule, module)
                .eq(action != null && !action.isBlank(), AuditLogEntity::getAction, action)
                .eq(result != null && !result.isBlank(), AuditLogEntity::getResult, result)
                .eq(traceId != null && !traceId.isBlank(), AuditLogEntity::getTraceId, traceId)
                .ge(startTime != null, AuditLogEntity::getCreatedAt, startTime)
                .le(endTime != null, AuditLogEntity::getCreatedAt, endTime)
                .orderByDesc(AuditLogEntity::getCreatedAt)
                .orderByDesc(AuditLogEntity::getId);

        Page<AuditLogEntity> entityPage = auditLogMapper.selectPage(new Page<>(page, safeSize), wrapper);
        // withLabels() 填中文显示标签（module/action 码值不变，问题修复 #2 显示层）
        List<AuditLogVO> vos = entityPage.getRecords().stream()
                .map(AuditLogVO::from)
                .map(AuditLogVO::withLabels)
                .toList();
        // 8x-1：username 写入侧快照缺失（worker/异步线程审计、refresh 早期写入"-"占位）时，
        // 查询侧按 userId 批量回填——每页最多 100 行一次 in 查询，不逐行回查 users 表
        backfillUsernames(vos);
        return R.ok(PageResult.of(vos, entityPage.getTotal(), page, safeSize));
    }

    /**
     * 8x-1：回填缺失的 username（仅显示层 VO，不回写 DB——写入侧冗余快照设计不变）。
     * 用户已删时回退「用户#id」，保证用户列不再出现"-"；userId 与 username 双空（系统级操作）
     * 交由前端兜底显示「系统」。
     */
    private void backfillUsernames(List<AuditLogVO> vos) {
        Set<Long> missing = vos.stream()
                .filter(vo -> vo.getUserId() != null && isBlankUsername(vo.getUsername()))
                .map(AuditLogVO::getUserId)
                .collect(Collectors.toSet());
        if (missing.isEmpty()) {
            return;
        }
        Map<Long, String> names = userMapper.selectBatchIds(missing).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
        for (AuditLogVO vo : vos) {
            if (vo.getUserId() != null && isBlankUsername(vo.getUsername())) {
                String name = names.get(vo.getUserId());
                vo.setUsername(name != null ? name : "用户#" + vo.getUserId());
            } else if (vo.getUserId() == null && isBlankUsername(vo.getUsername())) {
                vo.setUsername(usernameFromDetail(vo.getDetailJson()));
            }
        }
    }

    /** 空串/null/早期 refresh 审计写入的"-"占位都视为缺失。 */
    private boolean isBlankUsername(String username) {
        return username == null || username.isBlank() || "-".equals(username);
    }

    /**
     * 8x-1 第二档：userId 与 username 双空的历史行（旧版 SessionService 踢会话留痕未盖戳），
     * 从 detailJson 的 username 键恢复显示名。与 login 失败行显示请求账号的既有口径一致；
     * 无该键或解析失败 → 返回 null（前端兜底「系统」）。
     */
    private String usernameFromDetail(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(detailJson);
            String name = node.path("username").asText(null);
            return (name != null && !name.isBlank() && !"-".equals(name)) ? name : null;
        } catch (Exception e) {
            return null;
        }
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
