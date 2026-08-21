package com.superprogrammer.feedback.controller;

import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.ratelimit.RateLimit;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import com.superprogrammer.feedback.dto.CreateSuggestionRequest;
import com.superprogrammer.feedback.dto.SuggestionVO;
import com.superprogrammer.feedback.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 反馈中心·用户端点（19x，三合一「反馈与帮助」用户侧）。
 *
 * <pre>
 * POST /api/feedback/suggestions       提交建议（限流 5/60s/用户；附件≤3 属主校验）
 * GET  /api/feedback/suggestions/mine  我的建议分页（强制 self）
 * </pre>
 */
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final com.superprogrammer.feedback.service.FeedbackNotificationService notificationService;

    @PostMapping("/suggestions")
    @RateLimit(action = "feedback_suggestion", max = 5, windowSeconds = 60)
    @AuditLog(module = "feedback", action = "suggestion_submit", targetType = "feedback_suggestion")
    public ResponseEntity<R<Map<String, Long>>> submitSuggestion(@Valid @RequestBody CreateSuggestionRequest req) {
        Long id = feedbackService.submitSuggestion(currentUserId(), req);
        return ResponseEntity.ok(R.ok("建议已提交，感谢反馈", Map.of("id", id)));
    }

    @GetMapping("/suggestions/mine")
    public ResponseEntity<R<PageResult<SuggestionVO>>> mySuggestions(@RequestParam(defaultValue = "1") int page,
                                                                     @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(R.ok(feedbackService.mySuggestions(currentUserId(), page, size)));
    }

    // ---------- 站内通知（铃铛三件套） ----------

    /** 未读数（铃铛 3s 轮询；部分索引，响应极小）。 */
    @GetMapping("/notifications/count")
    public ResponseEntity<R<Map<String, Long>>> unreadCount() {
        return ResponseEntity.ok(R.ok(Map.of("count", notificationService.countUnread(currentUserId()))));
    }

    @GetMapping("/notifications")
    public ResponseEntity<R<PageResult<com.superprogrammer.feedback.entity.FeedbackNotificationEntity>>> notifications(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(R.ok(notificationService.myNotifications(currentUserId(), page, size)));
    }

    /** 标记已读（幂等；非本人静默）。 */
    @PostMapping("/notifications/{id}/read")
    public ResponseEntity<R<Void>> markRead(@org.springframework.web.bind.annotation.PathVariable("id") Long id) {
        notificationService.markRead(currentUserId(), id);
        return ResponseEntity.ok(R.ok("已读", null));
    }

    @PostMapping("/notifications/read-all")
    public ResponseEntity<R<Map<String, Integer>>> markAllRead() {
        return ResponseEntity.ok(R.ok("全部已读", Map.of("count", notificationService.markAllRead(currentUserId()))));
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getPrincipal() == null ? null : (Long) auth.getPrincipal();
    }
}
