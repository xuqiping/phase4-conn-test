package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.service.KnowledgeNodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库管理员端点（knowledge:manage）。
 *
 * <p>POST /api/knowledge/admin/backfill-tokens — 回填存量节点 content_tokens（Phase2 V35 jieba-BM25 词法兜底）。
 */
@RestController
@RequestMapping("/api/knowledge/admin")
@RequiredArgsConstructor
public class KnowledgeAdminController {

    private final KnowledgeNodeService knowledgeNodeService;

    @PostMapping("/backfill-tokens")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<Integer>> backfillTokens() {
        int updated = knowledgeNodeService.backfillContentTokens();
        return ResponseEntity.ok(R.ok("已回填 " + updated + " 个节点的 content_tokens", updated));
    }
}
