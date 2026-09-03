package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.dto.KnowledgeRelationRequest;
import com.superprogrammer.knowledge.dto.KnowledgeRelationVO;
import com.superprogrammer.knowledge.relation.DocumentRelationService;
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

/**
 * C1 文档关联（规格 §3.4 后端部分）。建议（suggestions）端点见 WP1 Step3 扩展。
 * 后端为唯一安全边界：Service 内逐请求 canManage/canRead 复核（Controller 注解仅粗档位）。
 */
@RestController
@RequestMapping("/api/knowledge/relations")
@RequiredArgsConstructor
public class KnowledgeRelationController {

    private final DocumentRelationService documentRelationService;

    /** 单文档视角边列表（出边+入边）。成员可读（理解关联带出证据来源）。 */
    @GetMapping
    @RequirePermission("knowledge:read")
    public ResponseEntity<R<List<KnowledgeRelationVO>>> list(
            @RequestParam Long kbId,
            @RequestParam Long docId) {
        return ResponseEntity.ok(R.ok(
                documentRelationService.listByDoc(kbId, docId, getCurrentUserId(), isAdmin())));
    }

    @PostMapping
    @RequirePermission("knowledge:write")
    @AuditLog(module = "kb", action = "kb_relation_create", targetType = "knowledge_document_relation")
    public ResponseEntity<R<KnowledgeRelationVO>> create(@Valid @RequestBody KnowledgeRelationRequest request) {
        return ResponseEntity.ok(R.ok("关联已建立",
                documentRelationService.create(request, getCurrentUserId(), isAdmin())));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("knowledge:write")
    @AuditLog(module = "kb", action = "kb_relation_delete", targetType = "knowledge_document_relation")
    public ResponseEntity<R<Void>> delete(@PathVariable Long id) {
        documentRelationService.delete(id, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("关联已删除", null));
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
