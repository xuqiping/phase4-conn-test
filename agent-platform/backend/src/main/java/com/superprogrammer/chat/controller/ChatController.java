package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.ChatRequest;
import com.superprogrammer.chat.dto.ChatResponse;
import com.superprogrammer.chat.dto.ChatTargetVO;
import com.superprogrammer.chat.dto.MemoryAssetUploadVO;
import com.superprogrammer.chat.dto.SessionVO;
import com.superprogrammer.chat.entity.ChatMessage;
import com.superprogrammer.chat.service.ChatSessionService;
import com.superprogrammer.chat.service.ChatTargetService;
import com.superprogrammer.chat.service.internal.MemoryAssetIngestService;
import com.superprogrammer.chat.service.internal.MemoryAssetUploadService;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    /**
     * SSE 超时（用户实测③/④ 2026-08-17）：原 120s 掐长生成（6000字文档 >2min 必 AsyncRequestTimeout，
     * 且超时路径曾误入 sendMessage 同步重答=双倍计费）。2026-08-17 用户拍板 600s→1200s（U4）。
     */
    private static final long SSE_TIMEOUT_MS = 1_200_000L;

    private final ChatSessionService chatSessionService;
    private final ChatTargetService chatTargetService;
    private final MemoryAssetUploadService memoryAssetUploadService;
    private final MemoryAssetIngestService memoryAssetIngestService;

    /** 聊天附件上传（V69 二期 P3，FR-201）：落盘 stored_files(CHAT) + 建文件记忆行（PROCESSING）。 */
    @PostMapping("/attachments")
    @AuditLog(module = "chat", action = "upload_attachment")
    // 安全体系 S4 · SEC-FR-124：上传频率限制（L5 补齐）
    @com.superprogrammer.common.ratelimit.RateLimit(action = "upload_file", max = 10, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    public ResponseEntity<R<MemoryAssetUploadVO>> uploadAttachment(@RequestParam("file") MultipartFile file) {
        Long userId = getCurrentUserId();
        return ResponseEntity.ok(R.ok(memoryAssetUploadService.upload(file, userId)));
    }

    /** 我的文件记忆列表（二期 P3 Step 2，记忆面板「文件记忆」页签数据源；
     *  5x 四轮 C6 增补 projectNames「收录于」徽标）。 */
    @GetMapping("/attachments")
    public ResponseEntity<R<List<com.superprogrammer.chat.dto.MemoryAssetMemoryVO>>> listAttachments() {
        Long userId = getCurrentUserId();
        return ResponseEntity.ok(R.ok(memoryAssetIngestService.listMine(userId)));
    }

    /** FAILED 文件记忆手动重试（二期 P3 Step 2，FR-202；retry_count 硬卡上限）。 */
    @PostMapping("/attachments/{memoryId}/retry")
    public ResponseEntity<R<Void>> retryAttachment(@PathVariable Long memoryId) {
        Long userId = getCurrentUserId();
        memoryAssetIngestService.retry(memoryId, userId);
        return ResponseEntity.ok(R.ok());
    }

    /** 我的文件记忆分块列表（二期 P3 Step 5，FR-203 文件卡片「展开分块」；仅 owner）。 */
    @GetMapping("/attachments/{memoryId}/chunks")
    public ResponseEntity<R<List<com.superprogrammer.chat.dto.FileChunkView>>> listAttachmentChunks(
            @PathVariable Long memoryId) {
        Long userId = getCurrentUserId();
        return ResponseEntity.ok(R.ok(memoryAssetIngestService.listChunks(memoryId, userId)));
    }

    /** 删除我的文件记忆（二期 P3 Step 4，FR-204：项目 FILE 条目同步失效 + 原文件硬删）。 */
    @DeleteMapping("/attachments/{memoryId}")
    public ResponseEntity<R<Void>> deleteAttachment(@PathVariable Long memoryId) {
        Long userId = getCurrentUserId();
        memoryAssetIngestService.delete(memoryId, userId);
        return ResponseEntity.ok(R.ok());
    }

    @PostMapping("/sessions")
    @com.superprogrammer.common.ratelimit.RateLimit(action = "chat_send", max = 20, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    public ResponseEntity<R<SessionVO>> createSession(@Valid @RequestBody ChatRequest request) {
        Long userId = getCurrentUserId();
        SessionVO session = chatSessionService.createSession(userId, request);
        return ResponseEntity.ok(R.ok(session));
    }

    @GetMapping("/sessions")
    public ResponseEntity<R<List<SessionVO>>> listSessions() {
        Long userId = getCurrentUserId();
        List<SessionVO> sessions = chatSessionService.listSessions(userId);
        return ResponseEntity.ok(R.ok(sessions));
    }

    @GetMapping("/targets")
    public ResponseEntity<R<List<ChatTargetVO>>> listTargets() {
        Long userId = getCurrentUserId();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(R.ok(chatTargetService.listTargets(userId, authentication)));
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<R<SessionVO>> getSession(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        SessionVO session = chatSessionService.getSession(userId, id);
        return ResponseEntity.ok(R.ok(session));
    }

    /** 批量删除会话（ownership 过滤，只删本人）。返实删条数。 */
    @DeleteMapping("/sessions/batch")
    public ResponseEntity<R<Integer>> deleteSessionsBatch(@RequestBody List<Long> ids) {
        Long userId = getCurrentUserId();
        int deleted = chatSessionService.deleteSessions(userId, ids);
        return ResponseEntity.ok(R.ok("已删除 " + deleted + " 个会话", deleted));
    }

    @PutMapping("/sessions/{id}/target")
    public ResponseEntity<R<SessionVO>> updateSessionTarget(
            @PathVariable Long id,
            @Valid @RequestBody ChatRequest request) {
        Long userId = getCurrentUserId();
        SessionVO session = chatSessionService.updateSessionTarget(userId, id, request);
        return ResponseEntity.ok(R.ok(session));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<R<Void>> deleteSession(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        chatSessionService.deleteSession(userId, id);
        return ResponseEntity.ok(R.ok());
    }

    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<R<List<ChatMessage>>> getSessionMessages(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        List<ChatMessage> messages = chatSessionService.getSessionMessages(userId, id);
        return ResponseEntity.ok(R.ok(messages));
    }

    @PostMapping("/sessions/{id}/messages")
    @com.superprogrammer.common.ratelimit.RateLimit(action = "chat_send", max = 20, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    public ResponseEntity<R<ChatResponse>> sendMessage(
            @PathVariable Long id,
            @Valid @RequestBody ChatRequest request) {
        Long userId = getCurrentUserId();
        request.setSessionId(id);
        ChatResponse response = chatSessionService.sendMessage(userId, request);
        return ResponseEntity.ok(R.ok(response));
    }

    @PostMapping("/messages")
    @com.superprogrammer.common.ratelimit.RateLimit(action = "chat_send", max = 20, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    public ResponseEntity<R<ChatResponse>> sendMessageNew(@Valid @RequestBody ChatRequest request) {
        Long userId = getCurrentUserId();
        ChatResponse response = chatSessionService.sendMessage(userId, request);
        return ResponseEntity.ok(R.ok(response));
    }

    @PostMapping(value = "/sessions/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @com.superprogrammer.common.ratelimit.RateLimit(action = "chat_send", max = 20, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    public SseEmitter sendMessageStream(
            @PathVariable Long id,
            @Valid @RequestBody ChatRequest request) {
        Long userId = getCurrentUserId();
        request.setSessionId(id);
        return doStream(userId, request);
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @com.superprogrammer.common.ratelimit.RateLimit(action = "chat_send", max = 20, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    public SseEmitter sendMessageNewStream(@Valid @RequestBody ChatRequest request) {
        Long userId = getCurrentUserId();
        return doStream(userId, request);
    }

    /**
     * 5x #7 收录确认点选（SSE 流）：ANSWER → 携服务端存原文全量回答流；DECLINE → 收尾消息流。
     * 前端只传 messageId+choice（不传内容，防篡改）；与发消息同一限流桶 chat_send。
     */
    @PostMapping(value = "/sessions/{id}/messages/{messageId}/inclusion-confirm",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @com.superprogrammer.common.ratelimit.RateLimit(action = "chat_send", max = 20, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    public SseEmitter confirmInclusion(@PathVariable Long id,
                                       @PathVariable Long messageId,
                                       @Valid @RequestBody com.superprogrammer.chat.dto.InclusionConfirmRequest body) {
        Long userId = getCurrentUserId();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        SecurityContext securityContext = SecurityContextHolder.getContext();
        java.util.Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        new Thread(() -> {
            try {
                SecurityContextHolder.setContext(securityContext);
                if (mdcSnapshot != null) {
                    MDC.setContextMap(mdcSnapshot);
                }
                // 计费归户：ANSWER 路径会调 LLM，须种 userId（同 doStream 范式）
                com.superprogrammer.billing.context.BillingContext.set(userId);
                AtomicBoolean sentDone = new AtomicBoolean(false);
                chatSessionService.confirmInclusion(userId, id, messageId, body.getChoice())
                        .doOnNext(evt -> {
                            try {
                                if ("DONE".equals(evt.getType())) {
                                    sentDone.set(true);
                                }
                                emitter.send(SseEmitter.event().data(evt));
                            } catch (Exception sendError) {
                                throw new RuntimeException(sendError);
                            }
                        })
                        .blockLast(java.time.Duration.ofMillis(SSE_TIMEOUT_MS));
                if (!sentDone.get()) {
                    emitter.send(SseEmitter.event().data(
                            com.superprogrammer.chat.dto.StreamEvent.done()));
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("收录确认流式失败", e);
                try {
                    emitter.send(SseEmitter.event().data(
                            com.superprogrammer.chat.dto.StreamEvent.error("服务器内部错误，请稍后重试")));
                    emitter.send(SseEmitter.event().data(
                            com.superprogrammer.chat.dto.StreamEvent.done()));
                    emitter.complete();
                } catch (Exception ignored) {}
            } finally {
                SecurityContextHolder.clearContext();
                com.superprogrammer.billing.context.BillingContext.clear();
                MDC.clear();
            }
        }).start();
        return emitter;
    }

    private SseEmitter doStream(Long userId, ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // 用户实测④：emitter 超时后连接已死——此时不得再走 sendMessage 同步重答（双倍计费+注定失败）
        AtomicBoolean emitterTimedOut = new AtomicBoolean(false);
        emitter.onTimeout(() -> emitterTimedOut.set(true));
        // 5x 四轮 U6：客户端断开（点停止 abort / 关页 / 断网）同样不得重答——send 失败即连接不可达
        AtomicBoolean clientGone = new AtomicBoolean(false);
        emitter.onError(t -> clientGone.set(true));
        SecurityContext securityContext = SecurityContextHolder.getContext();
        // 审计 #7：裸线程不继承 ThreadLocal，手工快照请求线程 MDC（traceId/userId/username/clientIp），
        // 线程内恢复——否则流式审计 fromMdc 读 username/userId 全 null（REST 路径走 Tomcat 线程不受影响）。
        java.util.Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        new Thread(() -> {
            try {
                SecurityContextHolder.setContext(securityContext);
                if (mdcSnapshot != null) {
                    MDC.setContextMap(mdcSnapshot);
                }
                // 计费归户：裸线程不继承 ThreadLocal，手工种 userId（流式链内 LLM 调用自动计费）
                com.superprogrammer.billing.context.BillingContext.set(userId);
                AtomicBoolean sentDone = new AtomicBoolean(false);
                chatSessionService.sendMessageStream(userId, request)
                        .doOnNext(evt -> {
                            try {
                                if ("DONE".equals(evt.getType())) {
                                    sentDone.set(true);
                                }
                                emitter.send(SseEmitter.event().data(evt));
                            } catch (Exception sendError) {
                                clientGone.set(true);
                                throw new RuntimeException(sendError);
                            }
                        })
                        .blockLast(java.time.Duration.ofMillis(SSE_TIMEOUT_MS));
                if (!sentDone.get()) {
                    emitter.send(SseEmitter.event().data(
                            com.superprogrammer.chat.dto.StreamEvent.done()));
                }
                emitter.complete();
            } catch (Exception e) {
                if (emitterTimedOut.get() || clientGone.get()) {
                    // SSE 超时/客户端断开路径：连接已死，跳过 sendMessage 同步重答（实测④双倍计费；
                    // U6 停止场景同理——上游已随 blockLast 取消，部分内容由 service doOnCancel 落库）
                    log.warn("SSE 超时/客户端断开（不重答）session={} messageLen={}",
                            request.getSessionId(), request.getMessage() == null ? 0 : request.getMessage().length());
                    return;
                }
                // Streaming failed or timed out — fall back to sync REST
                try {
                    ChatResponse response = chatSessionService.sendMessage(userId, request);
                    emitter.send(SseEmitter.event().data(
                            com.superprogrammer.chat.dto.StreamEvent.chunk(response.getContent())));
                    emitter.send(SseEmitter.event().data(
                            com.superprogrammer.chat.dto.StreamEvent.done()));
                    emitter.complete();
                } catch (Exception ex) {
                    // 安全审计 #7：ex.getMessage() 可能含内部细节，客户端回固定话术；完整异常写后端日志。
                    log.error("SSE 流式发送失败", ex);
                    try {
                        emitter.send(SseEmitter.event().data(
                                com.superprogrammer.chat.dto.StreamEvent.error("服务器内部错误，请稍后重试")));
                        emitter.send(SseEmitter.event().data(
                                com.superprogrammer.chat.dto.StreamEvent.done()));
                        emitter.complete();
                    } catch (Exception ignored) {}
                }
            } finally {
                SecurityContextHolder.clearContext();
                com.superprogrammer.billing.context.BillingContext.clear();
                MDC.clear();
            }
        }).start();

        return emitter;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (Long) auth.getPrincipal();
    }
}
