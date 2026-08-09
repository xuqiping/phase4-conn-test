package com.superprogrammer.knowledge.controller;

import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.dto.KnowledgeBaseRequest;
import com.superprogrammer.knowledge.dto.KnowledgeBaseVO;
import com.superprogrammer.knowledge.service.KnowledgeBaseService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge/bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping
    @RequirePermission("knowledge:read")
    public ResponseEntity<R<List<KnowledgeBaseVO>>> list() {
        return ResponseEntity.ok(R.ok(knowledgeBaseService.list(getCurrentUserId(), isAdmin())));
    }

    @GetMapping("/{id}")
    @RequirePermission("knowledge:read")
    public ResponseEntity<R<KnowledgeBaseVO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(knowledgeBaseService.get(id, getCurrentUserId(), isAdmin())));
    }

    @PostMapping
    @RequirePermission("knowledge:write")
    public ResponseEntity<R<KnowledgeBaseVO>> create(@Valid @RequestBody KnowledgeBaseRequest request) {
        return ResponseEntity.ok(R.ok("创建成功",
                knowledgeBaseService.create(request, getCurrentUserId())));
    }

    @PutMapping("/{id}")
    @RequirePermission("knowledge:write")
    public ResponseEntity<R<KnowledgeBaseVO>> update(@PathVariable Long id,
                                                      @Valid @RequestBody KnowledgeBaseRequest request) {
        return ResponseEntity.ok(R.ok(knowledgeBaseService.update(id, request, getCurrentUserId(), isAdmin())));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("knowledge:write")
    @AuditLog(module = "kb", action = "kb_delete", targetType = "knowledge_base")
    public ResponseEntity<R<Void>> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("删除成功", null));
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
