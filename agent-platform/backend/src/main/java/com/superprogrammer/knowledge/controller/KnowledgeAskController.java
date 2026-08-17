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
import jakarta.validation.Valid;
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
import org.slf4j.MDC;

/**
 * /api/knowledge/ask — RAG 流式问答（阶段5）。
 * retrieveEvidence（多KB，P4 求交）→ llmGateway.chatStream 流式生成 → CITATION（DONE 前）→ DONE。
 * 复用 chat SSE 接线（SseEmitter + blockLast 1200s + SecurityContext 透传）。
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
    @com.superprogrammer.common.ratelimit.RateLimit(action = "rag_ask", max = 10, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    public SseEmitter ask(@Valid @RequestBody AskRequest request) {
        Long userId = getCurrentUserId();
        boolean admin = isAdmin();
        SecurityContext securityContext = SecurityContextHolder.getContext();
        java.util.Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        // 同 ChatController.SSE_TIMEOUT_MS：1200s 覆盖长文生成（2026-08-17 用户拍板 U4）
        SseEmitter emitter = new SseEmitter(1_200_000L);

        new Thread(() -> {
            try {
                SecurityContextHolder.setContext(securityContext);
                if (mdcSnapshot != null) MDC.setContextMap(mdcSnapshot);
                // 计费归户：裸线程不继承 ThreadLocal，手工种 userId（RAG 流式生成段 + 查询扩展 LLM 调用自动计费）
                com.superprogrammer.billing.context.BillingContext.set(userId);
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
                }).blockLast(java.time.Duration.ofSeconds(1200));
                if (!sentDone.get()) {
                    emitter.send(SseEmitter.event().data(StreamEvent.done()));
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("/api/knowledge/ask 流式失败: {}", e.getMessage(), e);
                try {
                    // S3 Step4：固定话术出前端（原 e.getMessage 直发泄漏内部异常细节）；日志保留全量
                    emitter.send(SseEmitter.event().data(StreamEvent.error("知识库问答失败，请稍后重试")));
                    emitter.send(SseEmitter.event().data(StreamEvent.done()));
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

    Flux<StreamEvent> buildAskFlux(AskRequest request, Long userId, boolean admin) {
        List<Long> effective = ragScopeResolver.resolveEffectiveKbs(
                "CHAT", request.getKbIds(), null, null, userId, admin);
        if (effective.isEmpty()) {
            return Flux.just(StreamEvent.chunk("未配置可访问的知识库范围。"), StreamEvent.done());
        }
        RagRetrievalService.GroundedAskResult grounded =
                ragRetrievalService.retrieveGroundedAnswer(effective, request.getQuery(), userId, admin);
        EvidenceResult ev = grounded.evidence();
        if (ev.isAbstained()) {
            return Flux.just(StreamEvent.chunk(grounded.answer()), StreamEvent.ragState(grounded.confidenceState()),
                    StreamEvent.done());
        }
        String citationJson = toJson(ev);
        return Flux.just(StreamEvent.chunk(grounded.answer()), StreamEvent.citation(citationJson),
                StreamEvent.ragState(grounded.confidenceState()), StreamEvent.done());
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
