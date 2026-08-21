package com.superprogrammer.feedback.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import com.superprogrammer.feedback.dto.AdminSuggestionVO;
import com.superprogrammer.feedback.dto.ReviewSuggestionRequest;
import com.superprogrammer.feedback.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 反馈中心·admin 端点（19x）。审核岗权限码 feedback:manage（与内容岗 help:manage 分离）。
 *
 * <pre>
 * GET  /api/feedback/admin/suggestions            建议列表（筛状态分页，带 username+附件）
 * POST /api/feedback/admin/suggestions/{id}/review 审核（抢态；ADOPTED↔REJECTED 改判重发通知；CLOSED 终态）
 * </pre>
 */
@RestController
@RequestMapping("/api/feedback/admin")
@RequiredArgsConstructor
public class FeedbackAdminController {

    private final FeedbackService feedbackService;
    private final com.superprogrammer.feedback.service.FeedbackQuestionService questionService;

    @GetMapping("/suggestions")
    @RequirePermission("feedback:manage")
    public ResponseEntity<R<PageResult<AdminSuggestionVO>>> suggestions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(R.ok(feedbackService.adminSuggestions(status, page, size)));
    }

    @PostMapping("/suggestions/{id}/review")
    @RequirePermission("feedback:manage")
    @AuditLog(module = "feedback", action = "suggestion_review", targetType = "feedback_suggestion")
    public ResponseEntity<R<Void>> review(@PathVariable("id") Long id,
                                          @Valid @RequestBody ReviewSuggestionRequest req) {
        feedbackService.reviewSuggestion(id, req.toStatus(), req.reply(), currentUserId());
        return ResponseEntity.ok(R.ok("审核完成", null));
    }

    // ---------- 提问台（19x#2） ----------

    @GetMapping("/questions")
    @RequirePermission("feedback:manage")
    public ResponseEntity<R<PageResult<com.superprogrammer.feedback.dto.AdminQuestionVO>>> questions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(R.ok(questionService.adminQuestions(status, page, size)));
    }

    /** 回答：首答发通知，改答案不发；isPublic 随答案落（勾选即 FAQ 可见，取消即消失）。 */
    @PostMapping("/questions/{id}/answer")
    @RequirePermission("feedback:manage")
    @AuditLog(module = "feedback", action = "question_answer", targetType = "feedback_question")
    public ResponseEntity<R<Void>> answer(@PathVariable("id") Long id,
                                          @Valid @RequestBody com.superprogrammer.feedback.dto.AnswerQuestionRequest req) {
        questionService.answerQuestion(id, req.answer(), Boolean.TRUE.equals(req.isPublic()), currentUserId());
        return ResponseEntity.ok(R.ok("回答已保存", null));
    }

    @PostMapping("/questions/{id}/close")
    @RequirePermission("feedback:manage")
    @AuditLog(module = "feedback", action = "question_close", targetType = "feedback_question")
    public ResponseEntity<R<Void>> closeQuestion(@PathVariable("id") Long id) {
        questionService.closeQuestion(id);
        return ResponseEntity.ok(R.ok("已关闭", null));
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getPrincipal() == null ? null : (Long) auth.getPrincipal();
    }
}
