package com.superprogrammer.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.chat.dto.ChatRequest;
import com.superprogrammer.chat.dto.ChatResponse;
import com.superprogrammer.chat.dto.SessionVO;
import com.superprogrammer.chat.entity.ChatMessage;
import com.superprogrammer.chat.entity.ChatSession;
import com.superprogrammer.chat.mapper.ChatMessageMapper;
import com.superprogrammer.chat.mapper.ChatSessionMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.engine.OrchestrationEngine;
import com.superprogrammer.engine.context.ExecutionContext;
import com.superprogrammer.runtime.dto.ExecutionEvent;
import com.superprogrammer.runtime.service.RuntimeExecutionService;
import com.superprogrammer.workflow.entity.Workflow;
import com.superprogrammer.workflow.mapper.WorkflowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.superprogrammer.chat.dto.StreamEvent;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final AgentMapper agentMapper;
    private final WorkflowMapper workflowMapper;
    private final OrchestrationEngine orchestrationEngine;
    private final MemoryService memoryService;
    private final ChatTargetService chatTargetService;
    private final RuntimeExecutionService runtimeExecutionService;
    // 阶段5 RAG（CHAT 模式证据注入；AGENT=AgentRoutingStrategy firstStepConfigOverride；WORKFLOW=检索节点回调）
    private final com.superprogrammer.knowledge.service.RagScopeResolver ragScopeResolver;
    private final com.superprogrammer.knowledge.service.RagRetrievalService ragRetrievalService;
    private final com.superprogrammer.knowledge.service.internal.CitationChecker citationChecker;
    // 记忆模式开关解析（V26，session>agent/workflow>global，门控 RAG+记忆）
    private final com.superprogrammer.knowledge.service.RagModeResolver ragModeResolver;
    // 记忆冲突解决（V27）
    private final MemoryConflictService conflictService;
    private final com.superprogrammer.chat.service.internal.MemoryConflictJudge conflictJudge;

    private static final int MAX_CONTEXT_MESSAGES = 20;

    @Transactional
    public SessionVO createSession(Long userId, ChatRequest request) {
        chatTargetService.validateTarget(userId, request.getAgentId(), request.getWorkflowId());

        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setMode(resolveMode(request));
        session.setAgentId(request.getAgentId());
        session.setWorkflowId(request.getWorkflowId());
        session.setTitle(request.getMessage() != null && request.getMessage().length() > 30
                ? request.getMessage().substring(0, 30) + "..."
                : request.getMessage());
        session.setStatus("ACTIVE");
        if (request.getRagEnabled() != null) {
            session.setRagEnabled(request.getRagEnabled());   // 会话级记忆模式开关
        }
        if (request.getKbIds() != null && !request.getKbIds().isEmpty()) {
            session.setKbIds(request.getKbIds());   // CHAT 模式检索 scope（阶段5 RAG 绑定）
        }
        sessionMapper.insert(session);
        return toSessionVO(session);
    }

    public List<SessionVO> listSessions(Long userId) {
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getDeleted, 0)
                .orderByDesc(ChatSession::getUpdatedAt);
        return sessionMapper.selectList(wrapper).stream()
                .map(this::toSessionVO)
                .collect(Collectors.toList());
    }

    public SessionVO getSession(Long userId, Long sessionId) {
        ChatSession session = getSessionOrFail(userId, sessionId);
        return toSessionVO(session);
    }

    @Transactional
    public SessionVO updateSessionTarget(Long userId, Long sessionId, ChatRequest request) {
        chatTargetService.validateTarget(userId, request.getAgentId(), request.getWorkflowId());
        ChatSession session = getSessionOrFail(userId, sessionId);
        session.setMode(resolveMode(request));
        session.setAgentId(request.getAgentId());
        session.setWorkflowId(request.getWorkflowId());
        sessionMapper.updateById(session);
        return toSessionVO(session);
    }

    public List<ChatMessage> getSessionMessages(Long userId, Long sessionId) {
        getSessionOrFail(userId, sessionId);
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt);
        return messageMapper.selectList(wrapper);
    }

    @Transactional
    public void deleteSession(Long userId, Long sessionId) {
        ChatSession session = getSessionOrFail(userId, sessionId);
        sessionMapper.deleteById(sessionId);
    }

    @Transactional
    public ChatResponse sendMessage(Long userId, ChatRequest request) {
        ChatSession session;
        if (request.getSessionId() == null) {
            SessionVO vo = createSession(userId, request);
            session = sessionMapper.selectById(vo.getId());
        } else {
            session = getSessionOrFail(userId, request.getSessionId());
        }

        // Save user message
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(session.getId());
        userMsg.setRole("USER");
        userMsg.setContent(request.getMessage());
        messageMapper.insert(userMsg);

        // Build execution context
        ExecutionContext context = new ExecutionContext(
                session.getId(), session.getMode(), session.getAgentId(), session.getWorkflowId());
        context.setModel(request.getModel());
        context.setUserId(userId);

        // 记忆模式开关（V26）：持久化会话级覆盖 + 解析 effective + 线程化给策略
        if (request.getRagEnabled() != null && !request.getRagEnabled().equals(session.getRagEnabled())) {
            session.setRagEnabled(request.getRagEnabled());
            sessionMapper.updateById(session);
        }
        if (request.getKbIds() != null && !request.getKbIds().isEmpty()) {
            session.setKbIds(request.getKbIds());   // 阶段5 RAG：消息级更新检索 scope
            sessionMapper.updateById(session);
        }
        boolean ragOn = ragModeResolver.resolve(session.getMode(), session.getRagEnabled(),
                session.getAgentId(), session.getWorkflowId());
        context.setRagEnabled(ragOn);

        // 记忆冲突答复拦截（V27，仅记忆模式 ON）：有活跃 PENDING 时，本条消息先当冲突答复处理
        ConflictIntercept ci = interceptConflict(userId, session, request.getMessage(), ragOn);
        if (ci.handled()) {
            ChatMessage am = new ChatMessage();
            am.setSessionId(session.getId());
            am.setRole("ASSISTANT");
            am.setContent(ci.reply());
            messageMapper.insert(am);
            return ChatResponse.builder()
                    .sessionId(session.getId()).messageId(am.getId())
                    .content(ci.reply()).mode(session.getMode()).build();
        }

        // Load context window
        List<ChatMessage> history = loadContextWindow(session.getId());
        for (ChatMessage msg : history) {
            context.addMessage(msg.getRole(), msg.getContent());
        }

        // Load long-term memories（仅记忆模式开启）
        if (ragOn) {
            String memoryContext = memoryService.buildMemoryContext(userId);
            if (memoryContext != null && !memoryContext.isEmpty()) {
                context.addMessage("SYSTEM", "用户记忆:\n" + memoryContext);
            }
        }

        // 阶段5 RAG（仅 CHAT 模式证据注入；AGENT=AgentRoutingStrategy，WORKFLOW=检索节点）— 受记忆模式门控
        RagInjection rag = ragOn ? resolveRagForChat(session, request.getMessage(), userId) : RagInjection.none();
        if (rag.abstained()) {
            // abstain 短路：不调 LLM，直接落 ABSTAIN_MSG
            String abstain = rag.answer();
            String askText = ragOn ? memoryService.processMemory(userId, session.getId(), request.getMessage(), abstain) : null;
            String content = askText != null ? abstain + "\n\n" + askText : abstain;
            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setSessionId(session.getId());
            assistantMsg.setRole("ASSISTANT");
            assistantMsg.setContent(content);
            messageMapper.insert(assistantMsg);
            return ChatResponse.builder()
                    .sessionId(session.getId())
                    .messageId(assistantMsg.getId())
                    .content(content)
                    .mode(session.getMode())
                    .build();
        }
        if (rag.evidenceContext() != null) {
            context.addMessage("SYSTEM", rag.evidenceContext());
        }

        // Execute via engine
        String response = orchestrationEngine.execute(context, request.getMessage());

        // post-gen Citation 校验（A1，best-effort：chat 上下文富，失效 append disclaimer，不 regenerate）
        if (rag.injectedIndexes() != null && !rag.injectedIndexes().isEmpty()
                && citationChecker.extractAndCheck(response, rag.injectedIndexes()) == null) {
            response = response + "\n\n（注：回答中存在未经证据支持的引用编号，请核实原始知识库。）";
        }

        // 记忆冲突解决（V27）：同步抽取+判定，有冲突则 askText 追加进回复（插库前，保证同一轮投递）
        if (ragOn) {
            String askText = memoryService.processMemory(userId, session.getId(), request.getMessage(), response);
            if (askText != null) response = response + "\n\n" + askText;
        }

        // Save assistant message
        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setSessionId(session.getId());
        assistantMsg.setRole("ASSISTANT");
        assistantMsg.setContent(response);
        messageMapper.insert(assistantMsg);

        // 记忆冲突解决（V27）已在上方 processMemory 同步处理（生成回复后、插库前），此处无 async

        // Update session title on first exchange
        if (session.getTitle() == null && request.getMessage() != null) {
            session.setTitle(request.getMessage().length() > 30
                    ? request.getMessage().substring(0, 30) + "..."
                    : request.getMessage());
            sessionMapper.updateById(session);
        }

        return ChatResponse.builder()
                .sessionId(session.getId())
                .messageId(assistantMsg.getId())
                .content(response)
                .mode(session.getMode())
                .build();
    }

    // ---- 记忆冲突答复拦截（V27）----

    private record ConflictIntercept(boolean handled, String reply) {}

    /** 有活跃 PENDING 时把本条用户消息当冲突答复处理；答了→handled+确认；无关→flag 共存后放行。 */
    private ConflictIntercept interceptConflict(Long userId, ChatSession session, String userMessage, boolean ragOn) {
        if (!ragOn || userMessage == null) return new ConflictIntercept(false, null);
        com.superprogrammer.chat.entity.MemoryConflict pending = conflictService.getActivePendingOrExpire(session.getId(), userId);
        if (pending == null) return new ConflictIntercept(false, null);
        String routed = conflictJudge.route(pending.getAskText(), userMessage);
        String decision = extractDecision(routed);
        boolean isAnswer = routed != null && routed.contains("\"isAnswer\":true") && !"UNCLEAR".equals(decision);
        if (isAnswer) {
            conflictService.resolve(userId, pending.getId(), decision);
            String confirm = switch (decision) {
                case "KEEP_NEW" -> "好的，已保留新信息，删除旧记录。";
                case "KEEP_OLD" -> "好的，保留旧记录，忽略新信息。";
                case "KEEP_BOTH" -> "好的，两条都保留。";
                case "DISCARD" -> "好的，已删除该信息。";
                default -> "好的。";
            };
            return new ConflictIntercept(true, confirm);
        }
        // 无关 / UNCLEAR：flag 共存，继续正常处理本条
        conflictService.flag(pending);
        return new ConflictIntercept(false, null);
    }

    private String extractDecision(String routed) {
        if (routed == null) return "UNCLEAR";
        // 路由返回 keep: A=旧 / B=新 / BOTH / NONE
        if (routed.contains("\"keep\":\"B\"")) return "KEEP_NEW";
        if (routed.contains("\"keep\":\"A\"")) return "KEEP_OLD";
        if (routed.contains("\"keep\":\"BOTH\"")) return "KEEP_BOTH";
        if (routed.contains("\"keep\":\"NONE\"")) return "DISCARD";
        return "UNCLEAR";
    }

    private List<ChatMessage> loadContextWindow(Long sessionId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByDesc(ChatMessage::getCreatedAt)
                .last("LIMIT " + MAX_CONTEXT_MESSAGES);
        List<ChatMessage> messages = messageMapper.selectList(wrapper);
        java.util.Collections.reverse(messages);
        return messages;
    }

    public Flux<StreamEvent> sendMessageStream(Long userId, ChatRequest request) {
        ChatSession session;
        try {
            if (request.getSessionId() == null) {
                SessionVO vo = createSession(userId, request);
                session = sessionMapper.selectById(vo.getId());
            } else {
                session = getSessionOrFail(userId, request.getSessionId());
            }
        } catch (Exception e) {
            return Flux.just(StreamEvent.error(e.getMessage()), StreamEvent.done());
        }

        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(session.getId());
        userMsg.setRole("USER");
        userMsg.setContent(request.getMessage());
        messageMapper.insert(userMsg);

        ExecutionContext context = new ExecutionContext(
                session.getId(), session.getMode(), session.getAgentId(), session.getWorkflowId());
        context.setModel(request.getModel());
        context.setUserId(userId);

        // 记忆模式开关（V26）：持久化会话级覆盖 + 解析 effective + 线程化给策略
        if (request.getRagEnabled() != null && !request.getRagEnabled().equals(session.getRagEnabled())) {
            session.setRagEnabled(request.getRagEnabled());
            sessionMapper.updateById(session);
        }
        if (request.getKbIds() != null && !request.getKbIds().isEmpty()) {
            session.setKbIds(request.getKbIds());   // 阶段5 RAG：消息级更新检索 scope
            sessionMapper.updateById(session);
        }
        final boolean ragOn = ragModeResolver.resolve(session.getMode(), session.getRagEnabled(),
                session.getAgentId(), session.getWorkflowId());
        context.setRagEnabled(ragOn);

        // 记忆冲突答复拦截（V27，仅记忆模式 ON）
        ConflictIntercept ci = interceptConflict(userId, session, request.getMessage(), ragOn);
        if (ci.handled()) {
            return Flux.defer(() -> {
                ChatMessage am = new ChatMessage();
                am.setSessionId(session.getId());
                am.setRole("ASSISTANT");
                am.setContent(ci.reply());
                messageMapper.insert(am);
                return Flux.just(StreamEvent.chunk(ci.reply()), StreamEvent.done());
            });
        }

        List<ChatMessage> history = loadContextWindow(session.getId());
        for (ChatMessage msg : history) {
            context.addMessage(msg.getRole(), msg.getContent());
        }

        if (ragOn) {
            String memoryContext = memoryService.buildMemoryContext(userId);
            if (memoryContext != null && !memoryContext.isEmpty()) {
                context.addMessage("system", "用户记忆:\n" + memoryContext);
            }
        }

        // 阶段5 RAG（CHAT 模式证据注入；WORKFLOW 走检索节点回调，此处不注入）— 受记忆模式门控
        final RagInjection rag = ragOn ? resolveRagForChat(session, request.getMessage(), userId) : RagInjection.none();
        if (rag.abstained()) {
            // abstain 短路：发 ABSTAIN_MSG chunk + done，不调 LLM
            String abstain = rag.answer();
            String askText = ragOn ? memoryService.processMemory(userId, session.getId(), request.getMessage(), abstain) : null;
            String content = askText != null ? abstain + "\n\n" + askText : abstain;
            return Flux.defer(() -> {
                ChatMessage assistantMsg = new ChatMessage();
                assistantMsg.setSessionId(session.getId());
                assistantMsg.setRole("ASSISTANT");
                assistantMsg.setContent(content);
                messageMapper.insert(assistantMsg);
                return Flux.just(StreamEvent.chunk(content), StreamEvent.done());
            });
        }
        if (rag.evidenceContext() != null) {
            context.addMessage("system", rag.evidenceContext());
        }

        if ("WORKFLOW".equals(session.getMode())) {
            return streamWorkflow(userId, session, request);
        }

        Long sessionId = session.getId();
        StringBuilder fullResponse = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        AtomicBoolean hasError = new AtomicBoolean(false);

        return orchestrationEngine.executeStream(context, request.getMessage())
                .doOnNext(evt -> {
                    if ("CHUNK".equals(evt.getType()) && evt.getContent() != null) {
                        fullResponse.append(evt.getContent());
                    } else if ("THINKING".equals(evt.getType()) && evt.getContent() != null) {
                        fullThinking.append(evt.getContent());
                    } else if ("ERROR".equals(evt.getType())) {
                        hasError.set(true);
                    }
                })
                .concatWith(Flux.defer(() -> {
                    if (hasError.get()) {
                        return Flux.just(StreamEvent.done());
                    }

                    String responseText = fullResponse.toString();
                    // post-gen Citation 校验（A1 best-effort）：失效 append disclaimer + 发 chunk，不 regenerate
                    String disclaimer = null;
                    if (rag.injectedIndexes() != null && !rag.injectedIndexes().isEmpty()
                            && citationChecker.extractAndCheck(responseText, rag.injectedIndexes()) == null) {
                        disclaimer = "\n\n（注：回答中存在未经证据支持的引用编号，请核实原始知识库。）";
                        responseText = responseText + disclaimer;
                    }
                    // 记忆冲突解决（V27）：同步抽取+判定，有冲突则 askText 追加进回复 + 发 chunk
                    String askText = ragOn ? memoryService.processMemory(userId, sessionId, request.getMessage(), responseText) : null;
                    if (askText != null) {
                        responseText = responseText + "\n\n" + askText;
                    }
                    ChatMessage assistantMsg = new ChatMessage();
                    assistantMsg.setSessionId(sessionId);
                    assistantMsg.setRole("ASSISTANT");
                    assistantMsg.setContent(responseText);
                    if (fullThinking.length() > 0) {
                        try {
                            assistantMsg.setMetadata(
                                    new ObjectMapper().writeValueAsString(
                                            Map.of("thinking", fullThinking.toString())));
                        } catch (Exception ignored) {}
                    }
                    messageMapper.insert(assistantMsg);

                    if (askText != null) {
                        return Flux.just(StreamEvent.chunk("\n\n" + askText), StreamEvent.done());
                    }
                    if (disclaimer != null) {
                        return Flux.just(StreamEvent.chunk(disclaimer), StreamEvent.done());
                    }
                    return Flux.just(StreamEvent.done());
                }))
                .doOnError(e -> log.error("流式执行失败: {}", e.getMessage()));
    }

    private Flux<StreamEvent> streamWorkflow(Long userId, ChatSession session, ChatRequest request) {
        Long sessionId = session.getId();
        StringBuilder fullThinking = new StringBuilder();
        AtomicReference<String> finalResponse = new AtomicReference<>("");
        AtomicBoolean hasError = new AtomicBoolean(false);

        return runtimeExecutionService.runWorkflowFromChat(session.getWorkflowId(), userId, request.getMessage())
                .flatMapIterable(event -> workflowStreamEvents(event, fullThinking, finalResponse, hasError))
                .concatWith(Flux.defer(() -> {
                    if (hasError.get()) {
                        return Flux.just(StreamEvent.done());
                    }

                    String responseText = finalResponse.get();
                    // 记忆冲突解决（V27）：WORKFLOW 也走记忆模式门控 + 同步 processMemory
                    boolean wfRagOn = ragModeResolver.resolve(session.getMode(), session.getRagEnabled(),
                            session.getAgentId(), session.getWorkflowId());
                    String askText = wfRagOn ? memoryService.processMemory(userId, sessionId, request.getMessage(), responseText) : null;
                    if (askText != null) {
                        responseText = responseText + "\n\n" + askText;
                    }
                    ChatMessage assistantMsg = new ChatMessage();
                    assistantMsg.setSessionId(sessionId);
                    assistantMsg.setRole("ASSISTANT");
                    assistantMsg.setContent(responseText);
                    if (fullThinking.length() > 0) {
                        try {
                            assistantMsg.setMetadata(new ObjectMapper().writeValueAsString(
                                    Map.of("thinking", fullThinking.toString())));
                        } catch (Exception ignored) {}
                    }
                    messageMapper.insert(assistantMsg);
                    if (askText != null) {
                        return Flux.just(StreamEvent.chunk("\n\n" + askText), StreamEvent.done());
                    }
                    return Flux.just(StreamEvent.done());
                }))
                .onErrorResume(error -> Flux.just(StreamEvent.error(error.getMessage()), StreamEvent.done()))
                .doOnError(e -> log.error("Workflow streaming failed: {}", e.getMessage()));
    }

    private List<StreamEvent> workflowStreamEvents(
            ExecutionEvent event,
            StringBuilder fullThinking,
            AtomicReference<String> finalResponse,
            AtomicBoolean hasError) {
        List<StreamEvent> events = new ArrayList<>();
        String thinking = formatWorkflowThinking(event);
        if (thinking != null && !thinking.isBlank()) {
            fullThinking.append(thinking).append("\n");
            events.add(StreamEvent.thinking(thinking + "\n"));
        }

        if ("NODE_COMPLETED".equals(event.getType())) {
            String text = outputText(event);
            if (text != null && !text.isBlank()) {
                finalResponse.set(text);
            }
        } else if ("EXECUTION_FAILED".equals(event.getType())) {
            hasError.set(true);
            events.add(StreamEvent.error(workflowFailureMessage(event)));
        } else if ("EXECUTION_COMPLETED".equals(event.getType())) {
            String text = finalResponse.get();
            if (text == null || text.isBlank()) {
                text = "工作流执行完成，但没有生成文本输出";
                finalResponse.set(text);
            }
            events.add(StreamEvent.chunk(text));
        }
        return events;
    }

    private String formatWorkflowThinking(ExecutionEvent event) {
        String node = event.getNodeId() == null ? "工作流" : event.getNodeId();
        if ("EXECUTION_STARTED".equals(event.getType())) {
            return "开始执行工作流";
        }
        if ("NODE_STARTED".equals(event.getType())) {
            return "开始节点: " + node + formatMap("，输入 ", event.getInput());
        }
        if ("NODE_COMPLETED".equals(event.getType())) {
            return "完成节点: " + node + formatMap("，输出 ", event.getOutput());
        }
        if ("EXECUTION_COMPLETED".equals(event.getType())) {
            return "工作流执行完成";
        }
        if ("EXECUTION_FAILED".equals(event.getType())) {
            return "工作流执行失败: " + workflowFailureMessage(event);
        }
        return null;
    }

    private String formatMap(String prefix, Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return prefix + value;
    }

    private String outputText(ExecutionEvent event) {
        if (event.getOutput() == null) {
            return null;
        }
        Object text = event.getOutput().get("text");
        return text == null ? null : String.valueOf(text);
    }

    private String workflowFailureMessage(ExecutionEvent event) {
        if (event.getMetadata() != null && event.getMetadata().get("errorMessage") != null) {
            return String.valueOf(event.getMetadata().get("errorMessage"));
        }
        return event.getStatus() == null ? "工作流执行失败" : event.getStatus();
    }

    // ============================ 阶段5 RAG（CHAT 模式）============================

    /** RAG 注入结果：abstained→短路 ABSTAIN_MSG；否则 evidenceContext 注入 SYSTEM + injectedIndexes 供 post-gen 校验。 */
    private record RagInjection(boolean abstained, String answer, String evidenceContext,
                                java.util.Set<Integer> injectedIndexes) {
        static RagInjection none() {
            return new RagInjection(false, null, null, null);
        }
    }

    /**
     * 仅 CHAT 模式做证据注入（AGENT=AgentRoutingStrategy firstStepConfigOverride；WORKFLOW=检索节点回调）。
     * P4 求交 + 同模型约束在 RagScopeResolver；retrieveEvidence 不含生成（快）。
     */
    private RagInjection resolveRagForChat(ChatSession session, String query, Long userId) {
        if (session == null || !"CHAT".equals(session.getMode()) || query == null || query.isBlank()) {
            return RagInjection.none();
        }
        boolean admin = isAdmin();
        List<Long> effective = ragScopeResolver.resolveEffectiveKbs(
                session.getMode(), session.getKbIds(),
                session.getAgentId(), session.getWorkflowId(), userId, admin);
        if (effective.isEmpty()) {
            return RagInjection.none();   // 无可检索范围 → 普通聊天
        }
        com.superprogrammer.knowledge.dto.EvidenceResult ev =
                ragRetrievalService.retrieveEvidence(effective, query, userId, admin);
        if (ev.isAbstained()) {
            return new RagInjection(true, ev.getAnswer(), null, null);
        }
        return new RagInjection(false, null, ev.getSystemPrompt(), ev.getInjectedIndexes());
    }

    private boolean isAdmin() {
        try {
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            return auth != null && auth.getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .anyMatch(a -> "ROLE_admin".equalsIgnoreCase(a) || "ROLE_ADMIN".equalsIgnoreCase(a));
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveMode(ChatRequest request) {
        if (request.getWorkflowId() != null) {
            return "WORKFLOW";
        }
        if (request.getAgentId() != null) {
            return "AGENT";
        }
        return "CHAT";
    }

    private ChatSession getSessionOrFail(Long userId, Long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId) || session.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return session;
    }

    private SessionVO toSessionVO(ChatSession session) {
        String agentName = null;
        if (session.getAgentId() != null) {
            Agent agent = agentMapper.selectById(session.getAgentId());
            if (agent != null) agentName = agent.getName();
        }
        String workflowName = null;
        if (session.getWorkflowId() != null) {
            Workflow wf = workflowMapper.selectById(session.getWorkflowId());
            if (wf != null) workflowName = wf.getName();
        }
        return SessionVO.builder()
                .id(session.getId())
                .title(session.getTitle())
                .agentId(session.getAgentId())
                .agentName(agentName)
                .workflowId(session.getWorkflowId())
                .workflowName(workflowName)
                .mode(session.getMode())
                .status(session.getStatus())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }
}
