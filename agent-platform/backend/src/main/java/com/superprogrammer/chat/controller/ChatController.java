package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.ChatRequest;
import com.superprogrammer.chat.dto.ChatResponse;
import com.superprogrammer.chat.dto.ChatTargetVO;
import com.superprogrammer.chat.dto.SessionVO;
import com.superprogrammer.chat.entity.ChatMessage;
import com.superprogrammer.chat.service.ChatSessionService;
import com.superprogrammer.chat.service.ChatTargetService;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
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

        new Thread(() -> {
            try {
                SecurityContextHolder.setContext(securityContext);
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
            }
        }).start();

        return emitter;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (Long) auth.getPrincipal();
    }
}
