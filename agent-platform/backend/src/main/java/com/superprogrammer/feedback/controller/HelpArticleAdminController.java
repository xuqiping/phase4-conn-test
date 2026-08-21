package com.superprogrammer.feedback.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import com.superprogrammer.feedback.dto.UpsertArticleRequest;
import com.superprogrammer.feedback.entity.HelpArticleEntity;
import com.superprogrammer.feedback.service.HelpArticleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 说明台·admin 文章管理（19x#3，权限码 help:manage——内容岗，与审核岗 feedback:manage 分离）。
 *
 * <pre>
 * GET    /api/feedback/admin/help/articles              列表（含未发布）
 * POST   /api/feedback/admin/help/articles              新建（slug 冲突 409；默认未发布）
 * PUT    /api/feedback/admin/help/articles/{id}         更新（slug 不可改）
 * POST   /api/feedback/admin/help/articles/{id}/publish   发布/下架（body {published:true|false}）
 * DELETE /api/feedback/admin/help/articles/{id}         硬删（释放 slug；前端二次确认）
 * </pre>
 */
@RestController
@RequestMapping("/api/feedback/admin/help/articles")
@RequiredArgsConstructor
public class HelpArticleAdminController {

    private final HelpArticleService articleService;

    @GetMapping
    @RequirePermission("help:manage")
    public ResponseEntity<R<PageResult<HelpArticleEntity>>> list(@RequestParam(defaultValue = "1") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(R.ok(articleService.adminList(page, size)));
    }

    @PostMapping
    @RequirePermission("help:manage")
    @AuditLog(module = "feedback", action = "help_article_create", targetType = "help_article")
    public ResponseEntity<R<Map<String, Long>>> create(@Valid @RequestBody UpsertArticleRequest req) {
        return ResponseEntity.ok(R.ok("文章已创建", Map.of("id", articleService.create(req))));
    }

    @PutMapping("/{id}")
    @RequirePermission("help:manage")
    @AuditLog(module = "feedback", action = "help_article_update", targetType = "help_article")
    public ResponseEntity<R<Void>> update(@PathVariable("id") Long id,
                                          @Valid @RequestBody UpsertArticleRequest req) {
        articleService.update(id, req);
        return ResponseEntity.ok(R.ok("文章已更新", null));
    }

    @PostMapping("/{id}/publish")
    @RequirePermission("help:manage")
    @AuditLog(module = "feedback", action = "help_article_publish", targetType = "help_article")
    public ResponseEntity<R<Void>> publish(@PathVariable("id") Long id,
                                           @RequestBody Map<String, Boolean> body) {
        boolean published = Boolean.TRUE.equals(body.get("published"));
        articleService.setPublished(id, published);
        return ResponseEntity.ok(R.ok(published ? "已发布" : "已下架", null));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("help:manage")
    @AuditLog(module = "feedback", action = "help_article_delete", targetType = "help_article")
    public ResponseEntity<R<Void>> delete(@PathVariable("id") Long id) {
        articleService.delete(id);
        return ResponseEntity.ok(R.ok("已删除", null));
    }
}
