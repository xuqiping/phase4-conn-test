package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.dto.KnowledgeRelationRequest;
import com.superprogrammer.knowledge.dto.KnowledgeRelationVO;
import com.superprogrammer.knowledge.dto.KnowledgeRelationSuggestionVO;
import com.superprogrammer.knowledge.dto.RelationSuggestionAdoptRequest;
import com.superprogrammer.knowledge.relation.DocumentRelationService;
import com.superprogrammer.knowledge.relation.RelationSuggestionService;
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
 * C1 文档关联（规格 §3.4 后端部分）+ 关联建议（§3.3，Step3）。
 * 后端为唯一安全边界：Service 内逐请求 canManage/canRead 复核（Controller 注解仅粗档位）。
 */
@RestController
@RequestMapping("/api/knowledge/relations")
@RequiredArgsConstructor
public class KnowledgeRelationController {

    private final DocumentRelationService documentRelationService;
    private final RelationSuggestionService relationSuggestionService;

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

    // ==================== 关联建议（§3.3）====================

    /** 某库待裁决建议（仅 canManage——建议页是治理视图，非成员功能）。 */
    @GetMapping("/suggestions")
    @RequirePermission("knowledge:write")
    public ResponseEntity<R<List<KnowledgeRelationSuggestionVO>>> suggestions(@RequestParam Long kbId) {
        return ResponseEntity.ok(R.ok(
                relationSuggestionService.listByKb(kbId, getCurrentUserId(), isAdmin())));
    }

    /** 采纳：按指定方向/类型建边（复用建边全校验），建议置 ADOPTED。 */
    @PostMapping("/suggestions/{id}/adopt")
    @RequirePermission("knowledge:write")
    @AuditLog(module = "kb", action = "kb_relation_suggestion_adopt", targetType = "knowledge_document_relation_suggestion")
    public ResponseEntity<R<Void>> adoptSuggestion(
            @PathVariable Long id,
            @Valid @RequestBody RelationSuggestionAdoptRequest request) {
        relationSuggestionService.adopt(id, request, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("已采纳并建立关联", null));
    }

    /** 忽略：建议置 IGNORED，worker 不再重提该对。 */
    @PostMapping("/suggestions/{id}/ignore")
    @RequirePermission("knowledge:write")
    @AuditLog(module = "kb", action = "kb_relation_suggestion_ignore", targetType = "knowledge_document_relation_suggestion")
    public ResponseEntity<R<Void>> ignoreSuggestion(@PathVariable Long id) {
        relationSuggestionService.ignore(id, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("已忽略", null));
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
