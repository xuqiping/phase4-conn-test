package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.dto.KnowledgeConnectorRequest;
import com.superprogrammer.knowledge.dto.KnowledgeConnectorVO;
import com.superprogrammer.knowledge.service.KnowledgeConnectorService;
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

/**
 * C6 连接器管理（WP6 Step1）：KB 治理级 CRUD（owner/admin，服务层复校）。
 * 凭证只写不读——请求体 config 明文仅在内存停留，落库前 AES-GCM 加密（Service）。
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeConnectorController {

    private final KnowledgeConnectorService connectorService;

    @PostMapping("/kbs/{kbId}/connectors")
    @AuditLog(module = "kb", action = "connector_create", targetType = "connector")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<KnowledgeConnectorVO>> create(@PathVariable Long kbId,
                                                          @RequestBody KnowledgeConnectorRequest request) {
        return ResponseEntity.ok(R.ok("连接器已创建",
                connectorService.create(kbId, request, getCurrentUserId(), isAdmin())));
    }

    @GetMapping("/kbs/{kbId}/connectors")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<List<KnowledgeConnectorVO>>> list(@PathVariable Long kbId) {
        return ResponseEntity.ok(R.ok(connectorService.list(kbId, getCurrentUserId(), isAdmin())));
    }

    @PutMapping("/connectors/{id}")
    @AuditLog(module = "kb", action = "connector_update", targetType = "connector")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<KnowledgeConnectorVO>> update(@PathVariable Long id,
                                                          @RequestBody KnowledgeConnectorRequest request) {
        return ResponseEntity.ok(R.ok("连接器已更新",
                connectorService.update(id, request, getCurrentUserId(), isAdmin())));
    }

    @DeleteMapping("/connectors/{id}")
    @AuditLog(module = "kb", action = "connector_delete", targetType = "connector")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<Void>> delete(@PathVariable Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "连接器 id 非法");
        }
        connectorService.delete(id, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("连接器已删除（已同步文档保留，归手工管理）", null));
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
