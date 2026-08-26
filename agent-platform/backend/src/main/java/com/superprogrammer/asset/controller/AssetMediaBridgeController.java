package com.superprogrammer.asset.controller;

import com.superprogrammer.asset.dto.MediaImportRequest;
import com.superprogrammer.asset.dto.MediaImportVO;
import com.superprogrammer.asset.service.AssetMediaBridgeService;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 媒体生成产物 → 资产库 入库 REST（与 {@link AssetCanvasBridgeController} 画布→库并列）。
 *
 * <p>权限：第一层 {@code @RequirePermission("asset:write")}；第二层 service ACL（requireWrite 目标项目 +
 * 媒体归属校验，viewer 不可入库）。
 *
 * <p>端点（前缀 /api/assets）：
 * <pre>
 * POST /from-media   生图结果入库（生成→库；复用 SOURCE_MEDIA fileId，genMeta 标 MEDIA 来源）
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetMediaBridgeController {

    private final AssetMediaBridgeService bridgeService;

    /** 生图结果入库（生成→库）。requireWrite（viewer 不可入库）。修复III F1：同项目判重复用既有资产。 */
    @PostMapping("/from-media")
    @AuditLog(module = "asset", action = "save_to_library", targetType = "asset")
    @RequirePermission("asset:write")
    public ResponseEntity<R<MediaImportVO>> importFromMedia(@RequestBody MediaImportRequest req) {
        return ResponseEntity.ok(R.ok("已处理",
                bridgeService.importFromMediaTask(req, getCurrentUserId(), isAdmin())));
    }

    /**
     * 修复III F2（17x#1）：批量查媒体任务已入库状态（taskId→assetId，跨项目）。
     * 组产出 tab 行首「已入库」tag 数据源——一条 IN 查询防逐行 N+1；上限 50 个 taskId。
     */
    @GetMapping("/exists-by-source")
    @RequirePermission("asset:write")
    public ResponseEntity<R<Map<Long, Long>>> existsBySource(@RequestParam List<Long> taskIds) {
        if (taskIds.size() > 50) {
            return ResponseEntity.ok(R.ok(Map.of()));
        }
        return ResponseEntity.ok(R.ok(bridgeService.existsBySourceTaskIds(taskIds)));
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (Long) auth.getPrincipal();
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_admin".equalsIgnoreCase(a) || "ROLE_ADMIN".equalsIgnoreCase(a));
    }
}
