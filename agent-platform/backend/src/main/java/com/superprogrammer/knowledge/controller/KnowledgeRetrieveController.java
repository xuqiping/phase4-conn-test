package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.dto.RagRetrieveRequest;
import com.superprogrammer.knowledge.dto.RagRetrieveVO;
import com.superprogrammer.knowledge.service.RagRetrievalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 检索调试端点（v6 §4，阶段3）。
 * 返回候选 L0 / 证据 L2 / 引用 / 答案 / token 预算 / trace，供调试面板（阶段6 前端）。
 * Chat/Agent/Workflow 集成 + /ask SSE 在阶段5。
 */
@RestController
@RequestMapping("/api/knowledge/retrieve")
@RequiredArgsConstructor
public class KnowledgeRetrieveController {

    private final RagRetrievalService ragRetrievalService;

    @PostMapping
    @RequirePermission("knowledge:read")
    public ResponseEntity<R<RagRetrieveVO>> retrieve(@Valid @RequestBody RagRetrieveRequest request) {
        request.setAdminHint(isAdmin());
        return ResponseEntity.ok(R.ok(ragRetrievalService.retrieve(request, getCurrentUserId())));
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
