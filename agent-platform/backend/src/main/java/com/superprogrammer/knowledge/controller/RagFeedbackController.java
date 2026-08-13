package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.dto.RagFeedbackRequest;
import com.superprogrammer.knowledge.evaluation.FeedbackReviewService;
import com.superprogrammer.knowledge.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/api/knowledge/feedback")
@RequiredArgsConstructor
public class RagFeedbackController {
    private final FeedbackReviewService feedbackReviewService;
    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping
    @RequirePermission("knowledge:read")
    @AuditLog(module = "kb", action = "rag_feedback_submit", targetType = "rag_feedback")
    public ResponseEntity<R<FeedbackReviewService.Feedback>> submit(@RequestBody RagFeedbackRequest request) {
        if (request.knowledgeBaseId() == null) throw new IllegalArgumentException("knowledgeBaseId 不能为空");
        long userId = currentUserId();
        Long tenantId = knowledgeBaseService.ensure(request.knowledgeBaseId()).getTenantId();
        if (tenantId == null) throw new IllegalStateException("知识库缺少租户归属");
        return ResponseEntity.ok(R.ok("反馈已进入待审核队列", feedbackReviewService.submit(
                tenantId, request.knowledgeBaseId(), request.evaluationResultId(),
                request.category(), request.comment(), userId)));
    }

    @PostMapping("/{feedbackId}/approve")
    @RequirePermission("knowledge:manage")
    @AuditLog(module = "kb", action = "rag_feedback_approve", targetType = "rag_feedback")
    public ResponseEntity<R<FeedbackReviewService.Feedback>> approve(@PathVariable long feedbackId) {
        return ResponseEntity.ok(R.ok(feedbackReviewService.review(1L,feedbackId,true,currentUserId())));
    }

    @PostMapping("/{feedbackId}/reject")
    @RequirePermission("knowledge:manage")
    @AuditLog(module = "kb", action = "rag_feedback_reject", targetType = "rag_feedback")
    public ResponseEntity<R<FeedbackReviewService.Feedback>> reject(@PathVariable long feedbackId) {
        return ResponseEntity.ok(R.ok(feedbackReviewService.review(1L,feedbackId,false,currentUserId())));
    }

    private long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long id)) {
            throw new IllegalStateException("无法识别当前用户");
        }
        return id;
    }
}
