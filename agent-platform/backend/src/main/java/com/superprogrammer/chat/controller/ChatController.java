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

    private final ChatSessionService chatSessionService;
    private final ChatTargetService chatTargetService;
    private final MemoryAssetUploadService memoryAssetUploadService;
    private final MemoryAssetIngestService memoryAssetIngestService;

    /** 聊天附件上传（V69 二期 P3，FR-201）：落盘 stored_files(CHAT) + 建文件记忆行（PROCESSING）。 */
    @PostMapping("/attachments")
    @AuditLog(module = "chat", action = "upload_attachment")
    public ResponseEntity<R<MemoryAssetUploadVO>> uploadAttachment(@RequestParam("file") MultipartFile file) {
        Long userId = getCurrentUserId();
        return ResponseEntity.ok(R.ok(memoryAssetUploadService.upload(file, userId)));
    }

    /** 我的文件记忆列表（二期 P3 Step 2，记忆面板「文件记忆」页签数据源）。 */
    @GetMapping("/attachments")
    public ResponseEntity<R<List<com.superprogrammer.chat.entity.MemoryAssetMemory>>> listAttachments() {
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
    public ResponseEntity<R<SessionVO>> createSession(@RequestBody ChatRequest request) {
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
            @RequestBody ChatRequest request) {
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
    public ResponseEntity<R<ChatResponse>> sendMessage(
            @PathVariable Long id,
            @RequestBody ChatRequest request) {
        Long userId = getCurrentUserId();
        request.setSessionId(id);
        ChatResponse response = chatSessionService.sendMessage(userId, request);
        return ResponseEntity.ok(R.ok(response));
    }

    @PostMapping("/messages")
    public ResponseEntity<R<ChatResponse>> sendMessageNew(@RequestBody ChatRequest request) {
        Long userId = getCurrentUserId();
        ChatResponse response = chatSessionService.sendMessage(userId, request);
        return ResponseEntity.ok(R.ok(response));
    }

    @PostMapping(value = "/sessions/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(
            @PathVariable Long id,
            @RequestBody ChatRequest request) {
        Long userId = getCurrentUserId();
        request.setSessionId(id);
        return doStream(userId, request);
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageNewStream(@RequestBody ChatRequest request) {
        Long userId = getCurrentUserId();
        return doStream(userId, request);
    }

    private SseEmitter doStream(Long userId, ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);
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
                                throw new RuntimeException(sendError);
                            }
                        })
                        .blockLast(java.time.Duration.ofSeconds(120));
                if (!sentDone.get()) {
                    emitter.send(SseEmitter.event().data(
                            com.superprogrammer.chat.dto.StreamEvent.done()));
                }
                emitter.complete();
            } catch (Exception e) {
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
