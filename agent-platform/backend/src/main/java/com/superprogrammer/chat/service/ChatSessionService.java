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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.superprogrammer.chat.dto.StreamEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import com.superprogrammer.chat.service.internal.ExtractedFact;

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
    // 记忆项目 scope 解析（V33，读/写 scope 从 session 三列解析）
    private final MemoryScopeResolver memoryScopeResolver;
    // 记忆冲突解决（V27）
    private final MemoryConflictService conflictService;
    private final com.superprogrammer.chat.service.internal.MemoryConflictJudge conflictJudge;
    // 记忆处理模式开关（ASYNC=全异步/HYBRID=同步），全局 system_settings
    private final com.superprogrammer.system.service.SystemSettingService systemSettingService;
    // 记忆专用线程池（独立于 KB 索引）：ASYNC 记忆处理 orchestrator 在此跑，不 gate 回复
    @org.springframework.beans.factory.annotation.Qualifier("memoryTaskExecutor")
    private final java.util.concurrent.Executor memoryTaskExecutor;

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
        // 项目记忆 scope（V33）：建会话时带上写目标 + 读开关
        session.setProjectId(request.getProjectId());
        session.setMemIncludeGlobal(request.getMemIncludeGlobal());
        session.setMemReadProjectIds(request.getMemReadProjectIds());
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
        // 项目记忆 scope（V33）：切 target 时若前端带 scope 标记则一并持久化
        if (request.getMemIncludeGlobal() != null) {
            persistMemoryScope(session, request);
        }
        return toSessionVO(session);
    }

    /** 显式写 project scope 三列（projectId null=回总记忆，须显式 set 才能清，updateById 会跳过 null）。 */
    private void persistMemoryScope(ChatSession session, ChatRequest request) {
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChatSession> uw =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        uw.eq(ChatSession::getId, session.getId())
                .set(ChatSession::getProjectId, request.getProjectId())
                .set(ChatSession::getMemIncludeGlobal, request.getMemIncludeGlobal())
                // BIGINT[] 列须显式带 typeHandler：LambdaUpdateWrapper.set 不读实体 @TableField，裸 List 进 PG cast 失败
                .set(ChatSession::getMemReadProjectIds, request.getMemReadProjectIds(),
                        "typeHandler=com.superprogrammer.common.typehandler.LongArrayTypeHandler");
        sessionMapper.update(null, uw);
        session.setProjectId(request.getProjectId());
        session.setMemIncludeGlobal(request.getMemIncludeGlobal());
        session.setMemReadProjectIds(request.getMemReadProjectIds());
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

    /**
     * 批量删除会话（ownership 过滤：只删本人 id，非本人/不存在静默跳过）。
     * 用 inQuery 二次校验归属（同 MemoryService.deleteMemories 范式），避免直接信任前端 id 越权删。
     * 软删走 @TableLogic。返回实删条数。
     */
    @Transactional
    public int deleteSessions(Long userId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        List<Long> owned = sessionMapper.selectObjs(new LambdaQueryWrapper<ChatSession>()
                        .select(ChatSession::getId)
                        .eq(ChatSession::getUserId, userId)
                        .eq(ChatSession::getDeleted, 0)
                        .in(ChatSession::getId, ids))
                .stream().map(o -> (Long) o).toList();
        if (owned.isEmpty()) return 0;
        return sessionMapper.deleteBatchIds(owned);
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
        // 项目记忆 scope（V33）：前端发 memIncludeGlobal（scope 更新标记）→ 显式写三列（projectId null=回总记忆，须显式 set 才能清）
        if (request.getMemIncludeGlobal() != null) {
            persistMemoryScope(session, request);
        }
        // 读/写 scope 解析（admin=false：记忆 scope 是用户私有，admin 覆盖无需）
        com.superprogrammer.chat.service.internal.MemoryScope readScope =
                memoryScopeResolver.resolveReadScope(session, userId, false);
        com.superprogrammer.chat.service.internal.MemoryScope writeScope =
                memoryScopeResolver.resolveWriteScope(session, userId, false);
        Long writeTargetProjectId = memoryScopeResolver.resolveWriteTarget(session);
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
            String memoryContext = memoryService.buildMemoryContext(readScope, request.getMessage());
            if (memoryContext != null && !memoryContext.isEmpty()) {
                context.addMessage("SYSTEM", "用户记忆:\n" + memoryContext);
            }
        }

        // 阶段5 RAG（仅 CHAT 模式证据注入；AGENT=AgentRoutingStrategy，WORKFLOW=检索节点）— 受记忆模式门控
        RagInjection rag = ragOn ? resolveRagForChat(session, request.getMessage(), userId) : RagInjection.none();
        // 修 #2：abstain 不再短路当答案。无证据也照常调 LLM 生成，仅以系统提示告知"无知识库命中"，
        // 让 AI 基于用户记忆/自身能力回答，而不是吐死句子"未找到可访问的相关知识"。
        if (rag.abstained()) {
            context.addMessage("SYSTEM", "（知识库未检索到相关内容，请基于自身能力与用户记忆作答，不要编造引用编号。）");
        } else if (rag.evidenceContext() != null) {
            context.addMessage("SYSTEM", rag.evidenceContext());
        }

        // Execute via engine
        String response = orchestrationEngine.execute(context, request.getMessage());

        // post-gen Citation 校验（A1，best-effort：chat 上下文富，失效 append disclaimer，不 regenerate）
        if (rag.injectedIndexes() != null && !rag.injectedIndexes().isEmpty()
                && citationChecker.extractAndCheck(response, rag.injectedIndexes()) == null) {
            response = response + "\n\n（注：回答中存在未经证据支持的引用编号，请核实原始知识库。）";
        }

        // 记忆冲突解决：HYBRID=同步(即时 askText 追问，gate 回复) / ASYNC=异步(不卡回复，冲突进面板，前端轮询)
        if (ragOn) {
            if ("HYBRID".equals(systemSettingService.getMemoryProcessMode())) {
                String askText = memoryService.processMemory(writeScope, writeTargetProjectId, session.getId(), request.getMessage(), response);
                if (askText != null) response = response + "\n\n" + askText;
            } else {
                final String resp = response;
                try {
                    memoryTaskExecutor.execute(() -> memoryService.processMemory(writeScope, writeTargetProjectId, session.getId(), request.getMessage(), resp));
                } catch (java.util.concurrent.RejectedExecutionException ree) {
                    // AbortPolicy：池+队列满 → 拒绝。绝不回退 servlet 线程（RB-001 根因②），降级为 incident 提示。
                    log.warn("记忆异步任务被拒（池满），本次跳过 userId={}: {}", userId, ree.getMessage());
                    memoryService.recordIncident(userId, "系统繁忙，本次对话记忆未记录，请稍后重试。");
                }
            }
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
        com.superprogrammer.chat.service.internal.RouteResult routed = conflictJudge.route(pending.getAskText(), userMessage);
        String decision = routed.toDecision();
        if (routed.isAnswer() && !"UNCLEAR".equals(decision)) {
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
        // 修 #3：每个流事件都带上 sessionId，前端流式路径据此回读当前会话，避免每条消息新建会话
        final Long sid = session.getId();
        return doSendMessageStream(userId, request, session).map(e -> {
            e.setSessionId(sid);
            return e;
        });
    }

    private Flux<StreamEvent> doSendMessageStream(Long userId, ChatRequest request, ChatSession session) {
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
        if (request.getMemIncludeGlobal() != null) {
            persistMemoryScope(session, request);
        }
        // 读/写 scope 在 lambda 外解析（reactor 闭包安全；scope 是纯数据对象，无线程态）
        com.superprogrammer.chat.service.internal.MemoryScope readScope =
                memoryScopeResolver.resolveReadScope(session, userId, false);
        com.superprogrammer.chat.service.internal.MemoryScope writeScope =
                memoryScopeResolver.resolveWriteScope(session, userId, false);
        Long writeTargetProjectId = memoryScopeResolver.resolveWriteTarget(session);
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
            String memoryContext = memoryService.buildMemoryContext(readScope, request.getMessage());
            if (memoryContext != null && !memoryContext.isEmpty()) {
                context.addMessage("system", "用户记忆:\n" + memoryContext);
            }
        }

        // 阶段5 RAG（CHAT 模式证据注入；WORKFLOW 走检索节点回调，此处不注入）— 受记忆模式门控
        final RagInjection rag = ragOn ? resolveRagForChat(session, request.getMessage(), userId) : RagInjection.none();
        // 修 #2：abstain 不再短路当答案。无证据也照常走 executeStream 生成，仅以系统提示告知"无知识库命中"。
        if (rag.abstained()) {
            context.addMessage("system", "（知识库未检索到相关内容，请基于自身能力与用户记忆作答，不要编造引用编号。）");
        } else if (rag.evidenceContext() != null) {
            context.addMessage("system", rag.evidenceContext());
        }

        if ("WORKFLOW".equals(session.getMode())) {
            // 人机输入拦截（HUMAN_INPUT）：若该会话有 WAITING_INPUT 挂起，把本条消息当答案恢复执行
            Flux<ExecutionEvent> resumeFlux = runtimeExecutionService.resumeWorkflowFromChatAnswer(
                    session.getId(), request.getMessage(), userId);
            if (resumeFlux != null) {
                return streamWorkflowFlux(userId, session, request, resumeFlux);
            }
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
                    // 记忆处理：ASYNC=全异步(不卡,冲突走面板) / HYBRID=同步(即时 askText 追问)
                    boolean hybrid = "HYBRID".equals(systemSettingService.getMemoryProcessMode());
                    String askText = null;
                    if (ragOn) {
                        if (hybrid) {
                            askText = memoryService.processMemory(writeScope, writeTargetProjectId, sessionId, request.getMessage(), responseText);
                            if (askText != null) responseText = responseText + "\n\n" + askText;
                        } else {
                            final String rtMem = responseText;
                            try {
                                memoryTaskExecutor.execute(() -> memoryService.processMemory(writeScope, writeTargetProjectId, sessionId, request.getMessage(), rtMem));
                            } catch (java.util.concurrent.RejectedExecutionException ree) {
                                // AbortPolicy：池+队列满 → 拒绝。绝不回退 servlet 线程（RB-001 根因②），降级为 incident 提示。
                                log.warn("记忆异步任务被拒（池满），本次跳过 userId={}: {}", userId, ree.getMessage());
                                memoryService.recordIncident(userId, "系统繁忙，本次对话记忆未记录，请稍后重试。");
                            }
                        }
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

                    // P3：CITATION 事件（DONE 前发，前端聊天 [n] 引用回显；仅 RAG 命中且非空时）
                    java.util.List<com.superprogrammer.knowledge.dto.RagRetrieveVO.CitationVO> cites = rag.citations();
                    StreamEvent citationEvt = null;
                    if (cites != null && !cites.isEmpty()) {
                        try {
                            citationEvt = StreamEvent.citation(new ObjectMapper().writeValueAsString(cites));
                        } catch (Exception ignored) {}
                    }

                    java.util.List<StreamEvent> tail = new java.util.ArrayList<>();
                    if (askText != null) {
                        tail.add(StreamEvent.chunk("\n\n" + askText));
                    } else if (disclaimer != null) {
                        tail.add(StreamEvent.chunk(disclaimer));
                    }
                    if (citationEvt != null) {
                        tail.add(citationEvt);
                    }
                    tail.add(StreamEvent.done());
                    return Flux.fromIterable(tail);
                }).subscribeOn(Schedulers.boundedElastic()))
                .doOnError(e -> log.error("流式执行失败: {}", e.getMessage()));
    }

    private Flux<StreamEvent> streamWorkflow(Long userId, ChatSession session, ChatRequest request) {
        return streamWorkflowFlux(userId, session, request,
                runtimeExecutionService.runWorkflowFromChat(session.getWorkflowId(), userId, session.getId(), request.getMessage()));
    }

    /** 工作流事件流 → 对话流式事件的统一映射（首次执行与人机输入恢复复用）。 */
    private Flux<StreamEvent> streamWorkflowFlux(Long userId, ChatSession session, ChatRequest request,
                                                 Flux<ExecutionEvent> source) {
        Long sessionId = session.getId();
        StringBuilder fullThinking = new StringBuilder();
        AtomicReference<String> finalResponse = new AtomicReference<>("");
        AtomicBoolean hasError = new AtomicBoolean(false);
        // 记忆 scope（V33）：streamWorkflow 不经 doSendMessageStream 的解析，在此独立解析（lambda 外）
        com.superprogrammer.chat.service.internal.MemoryScope writeScope =
                memoryScopeResolver.resolveWriteScope(session, userId, false);
        Long writeTargetProjectId = memoryScopeResolver.resolveWriteTarget(session);

        return source
                .flatMapIterable(event -> workflowStreamEvents(event, fullThinking, finalResponse, hasError))
                .concatWith(Flux.defer(() -> {
                    if (hasError.get()) {
                        return Flux.just(StreamEvent.done());
                    }

                    String responseText = finalResponse.get();
                    // 记忆处理：WORKFLOW 也走门控；ASYNC=全异步 / HYBRID=同步即时追问
                    boolean wfRagOn = ragModeResolver.resolve(session.getMode(), session.getRagEnabled(),
                            session.getAgentId(), session.getWorkflowId());
                    boolean hybrid = "HYBRID".equals(systemSettingService.getMemoryProcessMode());
                    String askText = null;
                    if (wfRagOn) {
                        if (hybrid) {
                            askText = memoryService.processMemory(writeScope, writeTargetProjectId, sessionId, request.getMessage(), responseText);
                            if (askText != null) responseText = responseText + "\n\n" + askText;
                        } else {
                            final String rtMem = responseText;
                            try {
                                memoryTaskExecutor.execute(() -> memoryService.processMemory(writeScope, writeTargetProjectId, sessionId, request.getMessage(), rtMem));
                            } catch (java.util.concurrent.RejectedExecutionException ree) {
                                // AbortPolicy：池+队列满 → 拒绝。绝不回退 servlet 线程（RB-001 根因②），降级为 incident 提示。
                                log.warn("记忆异步任务被拒（池满），本次跳过 userId={}: {}", userId, ree.getMessage());
                                memoryService.recordIncident(userId, "系统繁忙，本次对话记忆未记录，请稍后重试。");
                            }
                        }
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
                }).subscribeOn(Schedulers.boundedElastic()))
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
        } else if ("WAITING_INPUT".equals(event.getType())) {
            // 工作流命中 HUMAN_INPUT：把问题作为本轮 assistant 文本流出 + 透出 INPUT_REQUIRED 结构化事件
            Map<String, Object> meta = event.getMetadata() == null ? Map.of() : event.getMetadata();
            String question = meta.get("question") == null ? "" : String.valueOf(meta.get("question"));
            finalResponse.set(question);
            if (!question.isBlank()) {
                events.add(StreamEvent.chunk(question));
            }
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("executionId", event.getExecutionId());
            payload.put("nodeId", event.getNodeId());
            payload.put("inputKey", meta.get("inputKey"));
            payload.put("question", question);
            payload.put("inputType", meta.getOrDefault("inputType", "text"));
            payload.put("options", meta.get("options"));
            payload.put("required", meta.getOrDefault("required", Boolean.TRUE));
            payload.put("placeholder", meta.get("placeholder"));
            events.add(StreamEvent.inputRequired(null, payload));
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

    /** RAG 注入结果：abstained→短路 ABSTAIN_MSG；否则 evidenceContext 注入 SYSTEM + injectedIndexes 供 post-gen 校验。
     *  P3：citations 透传给流式 CITATION 事件，前端聊天回显 [n] 引用（IMAGE 缩略图 / FILE 下载链）。 */
    private record RagInjection(boolean abstained, String answer, String evidenceContext,
                                java.util.Set<Integer> injectedIndexes,
                                java.util.List<com.superprogrammer.knowledge.dto.RagRetrieveVO.CitationVO> citations) {
        static RagInjection none() {
            return new RagInjection(false, null, null, null, null);
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
            return new RagInjection(true, ev.getAnswer(), null, null, null);
        }
        return new RagInjection(false, null, ev.getSystemPrompt(), ev.getInjectedIndexes(), ev.getCitations());
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
