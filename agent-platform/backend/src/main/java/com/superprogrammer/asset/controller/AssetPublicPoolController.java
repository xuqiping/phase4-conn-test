package com.superprogrammer.asset.controller;

import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.asset.dto.PublicProjectSummaryVO;
import com.superprogrammer.asset.dto.PublicPublishRequest;
import com.superprogrammer.asset.service.AssetPublicPoolService;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 资产公众池的摘要浏览、发布及移出接口。 */
@RestController
@RequestMapping("/api/assets/public-pool")
@RequiredArgsConstructor
public class AssetPublicPoolController {

    private final AssetPublicPoolService publicPoolService;

    @GetMapping
    @RequirePermission("asset:write")
    public ResponseEntity<R<List<PublicProjectSummaryVO>>> list(
            @RequestParam(required = false) Boolean official) {
        // 修复XI B1（XI-2）：official=true 只返管理员发布（publishedByAdmin）项目——
        // 导演台官方库浏览口径；不传=全量公众池（既有选择器回归口径）。
        return ResponseEntity.ok(R.ok(publicPoolService.listPublic(currentUserId(), isAdmin(), official)));
    }

    @AuditLog(module = "asset", action = "public_pool_publish", targetType = "asset_project")
    @PostMapping("/{projectId}/publish")
    @RequirePermission("asset:write")
    public ResponseEntity<R<Void>> publish(@PathVariable Long projectId,
                                            @RequestBody(required = false) PublicPublishRequest request) {
        publicPoolService.publish(projectId, currentUserId(), isAdmin(), request);
        return ResponseEntity.ok(R.ok("项目已发布到公众池", null));
    }

    @AuditLog(module = "asset", action = "public_pool_unpublish", targetType = "asset_project")
    @DeleteMapping("/{projectId}/publish")
    @RequirePermission("asset:write")
    public ResponseEntity<R<Void>> unpublish(@PathVariable Long projectId) {
        publicPoolService.unpublish(projectId, currentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("项目已移出公众池", null));
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : (Long) authentication.getPrincipal();
    }

    private boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> "ROLE_ADMIN".equalsIgnoreCase(authority));
    }
}
