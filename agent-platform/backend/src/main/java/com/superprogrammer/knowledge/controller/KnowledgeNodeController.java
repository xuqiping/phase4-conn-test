package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.dto.KnowledgeNodeVO;
import com.superprogrammer.knowledge.service.KnowledgeNodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识节点端点（目录树，必做收口 #4）。
 * 文档下节点 flat 列表，前端按 parentId 建树渲染文档大纲（L0 摘要 + L2 原文子节点）。
 */
@RestController
@RequestMapping("/api/knowledge/documents")
@RequiredArgsConstructor
public class KnowledgeNodeController {

    private final KnowledgeNodeService knowledgeNodeService;

    @GetMapping("/{docId}/nodes")
    @RequirePermission("knowledge:read")
    public ResponseEntity<R<List<KnowledgeNodeVO>>> listByDocument(@PathVariable Long docId) {
        return ResponseEntity.ok(R.ok(
                knowledgeNodeService.listByDocument(docId, getCurrentUserId(), isAdmin())));
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
