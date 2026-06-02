package com.superprogrammer.chat.service;

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
import com.superprogrammer.engine.OrchestrationEngine;
import com.superprogrammer.workflow.mapper.WorkflowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {

    @Mock private ChatSessionMapper sessionMapper;
    @Mock private ChatMessageMapper messageMapper;
    @Mock private AgentMapper agentMapper;
    @Mock private WorkflowMapper workflowMapper;
    @Mock private OrchestrationEngine orchestrationEngine;
    @Mock private MemoryService memoryService;

    @InjectMocks
    private ChatSessionService chatSessionService;

    private ChatSession testSession;

    @BeforeEach
    void setUp() {
        testSession = new ChatSession();
        testSession.setId(1L);
        testSession.setUserId(100L);
        testSession.setMode("CHAT");
        testSession.setStatus("ACTIVE");
        testSession.setDeleted(0);
    }

    @Test
    void createSession_defaultMode() {
        when(sessionMapper.insert(any(ChatSession.class))).thenAnswer(inv -> {
            ChatSession s = inv.getArgument(0);
            s.setId(1L);
            return 1;
        });

        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        SessionVO vo = chatSessionService.createSession(100L, request);

        assertEquals("CHAT", vo.getMode());
        assertEquals("Hello", vo.getTitle());
    }

    @Test
    void createSession_agentMode() {
        Agent agent = new Agent();
        agent.setId(10L);
        agent.setName("CodeBot");
        when(agentMapper.selectById(10L)).thenReturn(agent);
        when(sessionMapper.insert(any(ChatSession.class))).thenAnswer(inv -> {
            inv.getArgument(0, ChatSession.class).setId(1L);
            return 1;
        });

        ChatRequest request = new ChatRequest();
        request.setMessage("Fix my code");
        request.setAgentId(10L);
        SessionVO vo = chatSessionService.createSession(100L, request);

        assertEquals("AGENT", vo.getMode());
        assertEquals("CodeBot", vo.getAgentName());
    }

    @Test
    void createSession_workflowMode() {
        when(sessionMapper.insert(any(ChatSession.class))).thenAnswer(inv -> {
            inv.getArgument(0, ChatSession.class).setId(1L);
            return 1;
        });

        ChatRequest request = new ChatRequest();
        request.setMessage("Run pipeline");
        request.setWorkflowId(5L);
        SessionVO vo = chatSessionService.createSession(100L, request);

        assertEquals("WORKFLOW", vo.getMode());
    }

    @Test
    void createSession_truncateTitle() {
        when(sessionMapper.insert(any(ChatSession.class))).thenAnswer(inv -> {
            inv.getArgument(0, ChatSession.class).setId(1L);
            return 1;
        });

        ChatRequest request = new ChatRequest();
        request.setMessage("A".repeat(50));
        SessionVO vo = chatSessionService.createSession(100L, request);

        assertTrue(vo.getTitle().length() <= 33);
    }

    @Test
    void listSessions_returnsMappedVOs() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(testSession));

        List<SessionVO> vos = chatSessionService.listSessions(100L);

        assertEquals(1, vos.size());
        assertEquals(1L, vos.get(0).getId());
    }

    @Test
    void getSession_existingSession() {
        when(sessionMapper.selectById(1L)).thenReturn(testSession);
        SessionVO vo = chatSessionService.getSession(100L, 1L);
        assertEquals(1L, vo.getId());
    }

    @Test
    void getSession_notFound_throws() {
        when(sessionMapper.selectById(999L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> chatSessionService.getSession(100L, 999L));
    }

    @Test
    void getSession_wrongUser_throws() {
        when(sessionMapper.selectById(1L)).thenReturn(testSession);
        assertThrows(BusinessException.class, () -> chatSessionService.getSession(999L, 1L));
    }

    @Test
    void deleteSession_success() {
        when(sessionMapper.selectById(1L)).thenReturn(testSession);
        when(sessionMapper.deleteById(1L)).thenReturn(1);
        assertDoesNotThrow(() -> chatSessionService.deleteSession(100L, 1L));
    }

    @Test
    void sendMessage_createsSessionAndReturnsResponse() {
        when(sessionMapper.insert(any(ChatSession.class))).thenAnswer(inv -> {
            ChatSession s = inv.getArgument(0);
            s.setId(1L);
            return 1;
        });
        when(sessionMapper.selectById(1L)).thenAnswer(inv -> {
            ChatSession s = new ChatSession();
            s.setId(1L);
            s.setUserId(100L);
            s.setMode("CHAT");
            s.setStatus("ACTIVE");
            s.setDeleted(0);
            return s;
        });
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, ChatMessage.class).setId(10L);
            return 1;
        });
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(orchestrationEngine.execute(any(), eq("Hello"))).thenReturn("Hi there!");

        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        ChatResponse response = chatSessionService.sendMessage(100L, request);

        assertEquals("Hi there!", response.getContent());
        assertEquals("CHAT", response.getMode());
        assertEquals(1L, response.getSessionId());
    }

    @Test
    void sendMessage_existingSession() {
        when(sessionMapper.selectById(1L)).thenReturn(testSession);
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, ChatMessage.class).setId(10L);
            return 1;
        });
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(orchestrationEngine.execute(any(), eq("Hello"))).thenReturn("Response");

        ChatRequest request = new ChatRequest();
        request.setSessionId(1L);
        request.setMessage("Hello");
        ChatResponse response = chatSessionService.sendMessage(100L, request);

        assertEquals("Response", response.getContent());
    }
}
