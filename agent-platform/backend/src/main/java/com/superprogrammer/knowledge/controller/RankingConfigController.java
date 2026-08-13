package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.dto.RankingConfigUpdateRequest;
import com.superprogrammer.knowledge.service.KnowledgeBaseService;
import com.superprogrammer.knowledge.service.RankingConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 知识库 Ranking 配置 API：管理员默认与 KB 覆盖均显式配置、留审计。 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class RankingConfigController {

    private final RankingConfigService rankingConfigService;
    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping("/admin/ranking-config")
    @RequirePermission("role:manage")
    public ResponseEntity<R<RankingConfigService.ResolvedRankingConfig>> getDefault() {
        return ResponseEntity.ok(R.ok(rankingConfigService.resolve(null)));
    }

    @PutMapping("/admin/ranking-config")
    @RequirePermission("role:manage")
    @AuditLog(module = "kb", action = "ranking_default_update", targetType = "ranking_config")
    public ResponseEntity<R<RankingConfigService.ResolvedRankingConfig>> updateDefault(
            @Valid @RequestBody RankingConfigUpdateRequest request) {
        return ResponseEntity.ok(R.ok("知识库默认重排配置已更新",
                rankingConfigService.saveDefault(request, currentUserId())));
    }

    @GetMapping("/bases/{kbId}/ranking-config")
    @RequirePermission("knowledge:read")
    public ResponseEntity<R<RankingConfigService.ResolvedRankingConfig>> getForKb(@PathVariable Long kbId) {
        knowledgeBaseService.get(kbId, currentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok(rankingConfigService.resolve(kbId)));
    }

    @PutMapping("/bases/{kbId}/ranking-config")
    @RequirePermission("knowledge:write")
    @AuditLog(module = "kb", action = "ranking_config_update", targetType = "knowledge_base")
    public ResponseEntity<R<RankingConfigService.ResolvedRankingConfig>> updateForKb(
            @PathVariable Long kbId,
            @Valid @RequestBody RankingConfigUpdateRequest request) {
        Long userId = currentUserId();
        if (!knowledgeBaseService.canManage(kbId, userId, isAdmin())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或知识库创建者可修改重排配置");
        }
        return ResponseEntity.ok(R.ok("知识库重排配置已更新",
                rankingConfigService.saveForKb(kbId, request, userId)));
    }

    private Long currentUserId() {
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

