package com.superprogrammer.asset.controller;

import com.superprogrammer.asset.dto.PublicAccessDecisionRequest;
import com.superprogrammer.asset.dto.PublicAccessRequestVO;
import com.superprogrammer.asset.service.AssetPublicAccessService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 审批型公众池项目的申请、状态查询和 OWNER 审批接口。 */
@RestController
@RequestMapping("/api/assets/public-pool/{projectId}/requests")
@RequiredArgsConstructor
public class AssetPublicAccessController {

    private final AssetPublicAccessService publicAccessService;

    @PostMapping
    @RequirePermission("asset:write")
    public ResponseEntity<R<PublicAccessRequestVO>> request(@PathVariable Long projectId) {
        return ResponseEntity.ok(R.ok("申请已提交", publicAccessService.request(projectId, currentUserId())));
    }

    @GetMapping("/mine")
    @RequirePermission("asset:write")
    public ResponseEntity<R<PublicAccessRequestVO>> myStatus(@PathVariable Long projectId) {
        return ResponseEntity.ok(R.ok(publicAccessService.myStatus(projectId, currentUserId())));
    }

    @GetMapping
    @RequirePermission("asset:write")
    public ResponseEntity<R<List<PublicAccessRequestVO>>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(R.ok(publicAccessService.listForOwner(
                projectId, currentUserId(), isAdmin())));
    }

    @PutMapping("/{requestId}/decision")
    @RequirePermission("asset:write")
    public ResponseEntity<R<Void>> decide(@PathVariable Long projectId,
                                           @PathVariable Long requestId,
                                           @RequestBody PublicAccessDecisionRequest request) {
        publicAccessService.decide(projectId, requestId, currentUserId(), isAdmin(),
                request == null ? null : request.getDecision());
        return ResponseEntity.ok(R.ok("申请已处理", null));
    }

    @DeleteMapping("/{requestId}/approval")
    @RequirePermission("asset:write")
    public ResponseEntity<R<Void>> revoke(@PathVariable Long projectId,
                                           @PathVariable Long requestId) {
        publicAccessService.revoke(projectId, requestId, currentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("公共访问已撤销", null));
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
