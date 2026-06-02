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
import com.superprogrammer.workflow.entity.Workflow;
import com.superprogrammer.workflow.mapper.WorkflowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    private static final int MAX_CONTEXT_MESSAGES = 20;

    @Transactional
    public SessionVO createSession(Long userId, ChatRequest request) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setMode(resolveMode(request));
        session.setAgentId(request.getAgentId());
        session.setWorkflowId(request.getWorkflowId());
        session.setTitle(request.getMessage() != null && request.getMessage().length() > 30
                ? request.getMessage().substring(0, 30) + "..."
                : request.getMessage());
        session.setStatus("ACTIVE");
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

        // Load context window
        List<ChatMessage> history = loadContextWindow(session.getId());
        for (ChatMessage msg : history) {
            context.addMessage(msg.getRole(), msg.getContent());
        }

        // Load long-term memories
        String memoryContext = memoryService.buildMemoryContext(userId);
        if (memoryContext != null && !memoryContext.isEmpty()) {
            context.addMessage("SYSTEM", "用户记忆:\n" + memoryContext);
        }

        // Execute via engine
        String response = orchestrationEngine.execute(context, request.getMessage());

        // Save assistant message
        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setSessionId(session.getId());
        assistantMsg.setRole("ASSISTANT");
        assistantMsg.setContent(response);
        messageMapper.insert(assistantMsg);

        // Async memory extraction
        memoryService.extractMemoriesAsync(userId, request.getMessage(), response);

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

        List<ChatMessage> history = loadContextWindow(session.getId());
        for (ChatMessage msg : history) {
            context.addMessage(msg.getRole(), msg.getContent());
        }

        String memoryContext = memoryService.buildMemoryContext(userId);
        if (memoryContext != null && !memoryContext.isEmpty()) {
            context.addMessage("system", "用户记忆:\n" + memoryContext);
        }

        Long sessionId = session.getId();
        StringBuilder fullResponse = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();

        return orchestrationEngine.executeStream(context, request.getMessage())
                .doOnNext(evt -> {
                    if ("CHUNK".equals(evt.getType()) && evt.getContent() != null) {
                        fullResponse.append(evt.getContent());
                    } else if ("THINKING".equals(evt.getType()) && evt.getContent() != null) {
                        fullThinking.append(evt.getContent());
                    }
                })
                .concatWith(Flux.defer(() -> {
                    String responseText = fullResponse.toString();
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

                    memoryService.extractMemoriesAsync(userId, request.getMessage(), responseText);

                    return Flux.just(StreamEvent.done());
                }))
                .doOnError(e -> log.error("流式执行失败: {}", e.getMessage()));
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
