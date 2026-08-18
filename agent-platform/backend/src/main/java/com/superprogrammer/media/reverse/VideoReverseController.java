package com.superprogrammer.media.reverse;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.media.reverse.dto.LocalizeRequest;
import com.superprogrammer.media.reverse.dto.LocalizeResponse;
import com.superprogrammer.media.reverse.dto.ReverseAnalyzeRequest;
import com.superprogrammer.media.reverse.dto.ReverseAnalyzeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频反推 REST API（spec §4.1/§4.2，plan 计划6 Step2）。
 *
 * <p>同步接口（非任务队列）：analyze 含抽帧+单次 LLM，耗时段（秒级）——前端 120s 超时 + AbortController
 * 兜底（Step3/4 消费方约定）。限流口径（plan 运维）：analyze 3 次/分/用户（含 FFmpeg 成本），
 * localize 6 次/分/用户（纯 LLM）。二者均 {@code media:gen} 权限 + 审计。
 */
@Slf4j
@RestController
@RequestMapping("/api/media/reverse")
@RequiredArgsConstructor
public class VideoReverseController {

    private final VideoReverseService videoReverseService;

    /**
     * 反推分析：taskId/fileId 二选一 → 关键帧（恒返）+ 分镜表/剧本（modes 选配，单次多模态 LLM）。
     * 失败语义：源非法/超限 BAD_REQUEST；FFmpeg 故障/LLM 两次坏 JSON UNPROCESSABLE（固定话术不泄路径）。
     */
    @PostMapping("/analyze")
    @RequirePermission("media:gen")
    @com.superprogrammer.common.ratelimit.RateLimit(action = "media_reverse_analyze", max = 3, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    @AuditLog(module = "media", action = "reverse_analyze", targetType = "media_reverse")
    public ResponseEntity<R<ReverseAnalyzeResponse>> analyze(@RequestBody ReverseAnalyzeRequest request) {
        ReverseAnalyzeResponse resp = videoReverseService.analyze(request, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok(resp));
    }

    /**
     * 本土化转绘：剧本 + 目标地区 → 改写剧本 + 替换清单 + 结构告警（可用但标注，不阻断）。
     */
    @PostMapping("/localize")
    @RequirePermission("media:gen")
    @com.superprogrammer.common.ratelimit.RateLimit(action = "media_reverse_localize", max = 6, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    @AuditLog(module = "media", action = "reverse_localize", targetType = "media_reverse")
    public ResponseEntity<R<LocalizeResponse>> localize(@RequestBody LocalizeRequest request) {
        LocalizeResponse resp = videoReverseService.localize(request, getCurrentUserId());
        return ResponseEntity.ok(R.ok(resp));
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (Long) auth.getPrincipal();
    }

    /** admin 判定（与 MediaGenController 同口径）：admin 角色列表见全量任务，反推源校验同放行。 */
    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .map(a -> a.getAuthority() == null ? "" : a.getAuthority())
                .anyMatch(r -> "ROLE_admin".equals(r) || "ROLE_ADMIN".equals(r));
    }
}
