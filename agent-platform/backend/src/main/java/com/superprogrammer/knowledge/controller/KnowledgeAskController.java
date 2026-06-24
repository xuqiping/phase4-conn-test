package com.superprogrammer.knowledge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.knowledge.dto.AskRequest;
import com.superprogrammer.knowledge.dto.EvidenceResult;
import com.superprogrammer.knowledge.service.RagConfig;
import com.superprogrammer.knowledge.service.RagRetrievalService;
import com.superprogrammer.knowledge.service.RagScopeResolver;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * /api/knowledge/ask — RAG 流式问答（阶段5）。
 * retrieveEvidence（多KB，P4 求交）→ llmGateway.chatStream 流式生成 → CITATION（DONE 前）→ DONE。
 * 复用 chat SSE 接线（SseEmitter + blockLast 120s + SecurityContext 透传）。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeAskController {

    private final RagScopeResolver ragScopeResolver;
    private final RagRetrievalService ragRetrievalService;
    private final LlmGateway llmGateway;
    private final RagConfig ragConfig;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePermission("knowledge:read")
    public SseEmitter ask(@RequestBody AskRequest request) {
        Long userId = getCurrentUserId();
        boolean admin = isAdmin();
        SecurityContext securityContext = SecurityContextHolder.getContext();
        SseEmitter emitter = new SseEmitter(120_000L);

        new Thread(() -> {
            try {
                SecurityContextHolder.setContext(securityContext);
                Flux<StreamEvent> flux = buildAskFlux(request, userId, admin);
                AtomicBoolean sentDone = new AtomicBoolean(false);
                flux.doOnNext(evt -> {
                    try {
                        if ("DONE".equals(evt.getType())) {
                            sentDone.set(true);
                        }
                        emitter.send(SseEmitter.event().data(evt));
                    } catch (Exception sendError) {
                        throw new RuntimeException(sendError);
                    }
                }).blockLast(java.time.Duration.ofSeconds(120));
                if (!sentDone.get()) {
                    emitter.send(SseEmitter.event().data(StreamEvent.done()));
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("/api/knowledge/ask 流式失败: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().data(StreamEvent.error(e.getMessage())));
                    emitter.send(SseEmitter.event().data(StreamEvent.done()));
                    emitter.complete();
                } catch (Exception ignored) {}
            } finally {
                SecurityContextHolder.clearContext();
            }
        }).start();

        return emitter;
    }

    private Flux<StreamEvent> buildAskFlux(AskRequest request, Long userId, boolean admin) {
        List<Long> effective = ragScopeResolver.resolveEffectiveKbs(
                "CHAT", request.getKbIds(), null, null, userId, admin);
        if (effective.isEmpty()) {
            return Flux.just(StreamEvent.chunk("未配置可访问的知识库范围。"), StreamEvent.done());
        }
        EvidenceResult ev = ragRetrievalService.retrieveEvidence(effective, request.getQuery(), userId, admin);
        if (ev.isAbstained()) {
            return Flux.just(StreamEvent.chunk(ev.getAnswer()), StreamEvent.done());
        }
        LlmRequest llmReq = LlmRequest.builder()
                .model(ragConfig.getChatModel())
                .messages(List.of(
                        LlmMessage.builder().role("system").content(ev.getSystemPrompt()).build(),
                        LlmMessage.builder().role("user").content(request.getQuery()).build()))
                .temperature(ragConfig.getChatTemperature())
                .maxTokens(ragConfig.getChatMaxTokens())
                .stream(true)
                .build();
        String citationJson = toJson(ev);
        // CITATION 必须在 DONE 前
        return llmGateway.chatStream(llmReq, userId)
                .concatWith(Flux.just(StreamEvent.citation(citationJson), StreamEvent.done()));
    }

    private String toJson(EvidenceResult ev) {
        try {
            return objectMapper.writeValueAsString(ev.getCitations() == null ? List.of() : ev.getCitations());
        } catch (Exception e) {
            return "[]";
        }
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (Long) auth.getPrincipal();
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_admin".equalsIgnoreCase(a) || "ROLE_ADMIN".equalsIgnoreCase(a));
    }
}
