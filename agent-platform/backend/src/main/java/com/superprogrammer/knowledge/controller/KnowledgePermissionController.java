package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.dto.KnowledgePermissionRequest;
import com.superprogrammer.knowledge.dto.KnowledgePermissionVO;
import com.superprogrammer.knowledge.service.KnowledgePermissionService;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/knowledge/permissions")
@RequiredArgsConstructor
public class KnowledgePermissionController {

    private final KnowledgePermissionService knowledgePermissionService;

    @GetMapping
    @RequirePermission("knowledge:write")
    public ResponseEntity<R<List<KnowledgePermissionVO>>> list(
            @RequestParam String targetType,
            @RequestParam Long targetId) {
        return ResponseEntity.ok(R.ok(
                knowledgePermissionService.listGrants(targetType, targetId, getCurrentUserId(), isAdmin())));
    }

    @PostMapping
    @RequirePermission("knowledge:write")
    @AuditLog(module = "kb", action = "kb_grant", targetType = "knowledge_permission")
    public ResponseEntity<R<KnowledgePermissionVO>> grant(@Valid @RequestBody KnowledgePermissionRequest request) {
        return ResponseEntity.ok(R.ok("授权成功",
                knowledgePermissionService.grant(request, getCurrentUserId(), isAdmin())));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("knowledge:write")
    @AuditLog(module = "kb", action = "kb_revoke", targetType = "knowledge_permission")
    public ResponseEntity<R<Void>> revoke(@PathVariable Long id) {
        knowledgePermissionService.revoke(id, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("撤销成功", null));
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
