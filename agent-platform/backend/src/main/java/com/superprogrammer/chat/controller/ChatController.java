package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.ChatRequest;
import com.superprogrammer.chat.dto.ChatResponse;
import com.superprogrammer.chat.dto.SessionVO;
import com.superprogrammer.chat.entity.ChatMessage;
import com.superprogrammer.chat.service.ChatSessionService;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatSessionService chatSessionService;

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

    @GetMapping("/sessions/{id}")
    public ResponseEntity<R<SessionVO>> getSession(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        SessionVO session = chatSessionService.getSession(userId, id);
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

        new Thread(() -> {
            try {
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
                    try {
                        emitter.send(SseEmitter.event().data(
                                com.superprogrammer.chat.dto.StreamEvent.error(ex.getMessage())));
                        emitter.send(SseEmitter.event().data(
                                com.superprogrammer.chat.dto.StreamEvent.done()));
                        emitter.complete();
                    } catch (Exception ignored) {}
                }
            }
        }).start();

        return emitter;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (Long) auth.getPrincipal();
    }
}
