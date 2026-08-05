package com.superprogrammer.asset.controller;

import com.superprogrammer.asset.dto.AssetUsageVO;
import com.superprogrammer.asset.dto.CanvasImportRequest;
import com.superprogrammer.asset.dto.CanvasImportVO;
import com.superprogrammer.asset.dto.ResolveRequest;
import com.superprogrammer.asset.dto.ResolveVO;
import com.superprogrammer.asset.service.AssetCanvasBridgeService;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目资产库·画布双向打通 REST API（plan §S7 / FR-008/009/011，设计方案 §八）。
 *
 * <p>权限：第一层 {@code @RequirePermission("asset:write")}；第二层 service 层 ACL（viewer 可 resolve/usages，不可 import）。
 *
 * <p>端点（前缀 /api/assets）：
 * <pre>
 * POST /canvas-import            画布节点产出入库（画布→库；PRODUCED 绑定 + 重复入库检测）
 * POST /assets/{id}/resolve      引用解析=当前/指定版本 fileId+content（库→画布；viewer 可用）
 * GET  /assets/{id}/usages       使用记录列表（双向追溯）
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetCanvasBridgeController {

    private final AssetCanvasBridgeService bridgeService;

    /** 画布节点产出入库（画布→库）。requireWrite（viewer 不可入库）。 */
    @PostMapping("/canvas-import")
    @RequirePermission("asset:write")
    public ResponseEntity<R<CanvasImportVO>> importFromCanvas(@RequestBody CanvasImportRequest req) {
        return ResponseEntity.ok(R.ok("已处理",
                bridgeService.importFromCanvas(req, getCurrentUserId(), isAdmin())));
    }

    /** 引用解析（库→画布）。loadAccessible（viewer 可读引用，安全清单防 fileId 遍历）。 */
    @PostMapping("/assets/{id}/resolve")
    @RequirePermission("asset:write")
    public ResponseEntity<R<ResolveVO>> resolve(@PathVariable Long id,
                                                @RequestBody(required = false) ResolveRequest req) {
        Integer version = req == null ? null : req.getVersion();
        return ResponseEntity.ok(R.ok(bridgeService.resolve(id, version, getCurrentUserId(), isAdmin())));
    }

    /** 使用记录列表（双向追溯）。loadAccessible（viewer 可读）。 */
    @GetMapping("/assets/{id}/usages")
    @RequirePermission("asset:write")
    public ResponseEntity<R<List<AssetUsageVO>>> usages(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(bridgeService.listUsages(id, getCurrentUserId(), isAdmin())));
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
