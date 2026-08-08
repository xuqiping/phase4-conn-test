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
import com.superprogrammer.runtime.dto.ExecutionEvent;
import com.superprogrammer.runtime.service.RuntimeExecutionService;
import com.superprogrammer.workflow.entity.Workflow;
import com.superprogrammer.workflow.mapper.WorkflowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

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
    // H' 切流：记忆召回/写入改接新栈，legacy MemoryService/MemoryConflictService/MemoryConflictJudge 待 H'-3 整块废
    @Mock private com.superprogrammer.chat.service.internal.MemoryRecallPipeline memoryRecallPipeline;
    @Mock private com.superprogrammer.chat.service.internal.MemoryGenerationService memoryGenerationService;
    @Mock private com.superprogrammer.chat.service.internal.MemoryRecallScopePreferenceService memoryRecallPrefService;
    @Mock private ChatTargetService chatTargetService;
    @Mock private RuntimeExecutionService runtimeExecutionService;
    // RAG/记忆集成后新增依赖（阶段5）；mock 默认让 ragModeResolver.resolve()→false → 跳 RAG/记忆路径
    @Mock private com.superprogrammer.knowledge.service.RagScopeResolver ragScopeResolver;
    @Mock private com.superprogrammer.knowledge.service.RagRetrievalService ragRetrievalService;
    @Mock private com.superprogrammer.knowledge.service.internal.CitationChecker citationChecker;
    @Mock private com.superprogrammer.knowledge.service.RagModeResolver ragModeResolver;
    // 联网搜索总开关等系统设置
    @Mock private com.superprogrammer.system.service.SystemSettingService systemSettingService;
    // 聊天附件归属校验（V69 二期 P3）
    @Mock private com.superprogrammer.chat.service.internal.MemoryAssetUploadService memoryAssetUploadService;

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
        verify(chatTargetService).validateTarget(100L, 10L, null);
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
        verify(chatTargetService).validateTarget(100L, null, 5L);
    }

    @Test
    void createSession_rejectsBothAgentAndWorkflow() {
        doThrow(new BusinessException(com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST, "不能同时选择智能体和工作流"))
                .when(chatTargetService).validateTarget(100L, 10L, 5L);

        ChatRequest request = new ChatRequest();
        request.setMessage("Run both");
        request.setAgentId(10L);
        request.setWorkflowId(5L);

        assertThrows(BusinessException.class, () -> chatSessionService.createSession(100L, request));
        verify(sessionMapper, never()).insert(any(ChatSession.class));
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
    void updateSessionTarget_changesExistingSessionWorkflow() {
        Workflow workflow = new Workflow();
        workflow.setId(8L);
        workflow.setName("New Workflow");
        when(sessionMapper.selectById(1L)).thenReturn(testSession);
        when(workflowMapper.selectById(8L)).thenReturn(workflow);

        ChatRequest request = new ChatRequest();
        request.setWorkflowId(8L);

        SessionVO vo = chatSessionService.updateSessionTarget(100L, 1L, request);

        assertEquals("WORKFLOW", vo.getMode());
        assertEquals(8L, vo.getWorkflowId());
        assertEquals("New Workflow", vo.getWorkflowName());
        verify(chatTargetService).validateTarget(100L, null, 8L);
        verify(sessionMapper).updateById(argThat(session ->
                session.getId().equals(1L)
                        && "WORKFLOW".equals(session.getMode())
                        && session.getWorkflowId().equals(8L)
                        && session.getAgentId() == null));
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

    // ---- H' 切流：记忆召回/写入走新栈（pipeline + generationService），不走 legacy MemoryService ----

    @Test
    void sendMessage_ragOn_recallsViaPipelineAndWritesViaGenerationService() {
        when(sessionMapper.selectById(1L)).thenReturn(testSession);
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, ChatMessage.class).setId(10L);
            return 1;
        });
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(ragModeResolver.resolve(eq("CHAT"), any(), any(), any())).thenReturn(true);
        // RAG 检索范围空 → 不注入证据，仅记忆
        when(ragScopeResolver.resolveEffectiveKbs(any(), any(), any(), any(), eq(100L), anyBoolean())).thenReturn(List.of());
        // 召回：持久化偏好 + pipeline 装配文本
        com.superprogrammer.chat.dto.MemoryRecallScopeRequest pref = new com.superprogrammer.chat.dto.MemoryRecallScopeRequest();
        pref.setPersonalOn(true);
        when(memoryRecallPrefService.getScope(100L)).thenReturn(pref);
        when(memoryRecallPipeline.recall(eq("Hello"), any(), eq(100L))).thenReturn(
                com.superprogrammer.chat.dto.MemoryRecallResult.builder().assembledText("用户偏好深空主题").build());
        when(orchestrationEngine.execute(any(), eq("Hello"))).thenReturn("回复");

        ChatRequest request = new ChatRequest();
        request.setSessionId(1L);
        request.setMessage("Hello");
        ChatResponse response = chatSessionService.sendMessage(100L, request);

        // 召回文本随 LLM 上下文进引擎；新栈写入提交一次
        verify(memoryRecallPipeline).recall(eq("Hello"), argThat(r -> Boolean.TRUE.equals(r.getPersonalOn())), eq(100L));
        verify(memoryGenerationService).processTurnAsync(eq(100L), eq(1L), eq("Hello"), eq("回复"));
        assertEquals("回复", response.getContent());
    }

    @Test
    void sendMessage_ragOn_emptyRecall_doesNotCrash() {
        when(sessionMapper.selectById(1L)).thenReturn(testSession);
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, ChatMessage.class).setId(10L);
            return 1;
        });
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(ragModeResolver.resolve(eq("CHAT"), any(), any(), any())).thenReturn(true);
        when(ragScopeResolver.resolveEffectiveKbs(any(), any(), any(), any(), eq(100L), anyBoolean())).thenReturn(List.of());
        // 召回装配空串
        when(memoryRecallPrefService.getScope(100L)).thenReturn(null);
        when(memoryRecallPipeline.recall(eq("Hello"), any(), eq(100L))).thenReturn(
                com.superprogrammer.chat.dto.MemoryRecallResult.builder().assembledText("").build());
        when(orchestrationEngine.execute(any(), eq("Hello"))).thenReturn("回复");

        ChatRequest request = new ChatRequest();
        request.setSessionId(1L);
        request.setMessage("Hello");
        ChatResponse response = chatSessionService.sendMessage(100L, request);

        // 空召回不崩，写入仍提交
        assertEquals("回复", response.getContent());
        verify(memoryGenerationService).processTurnAsync(eq(100L), eq(1L), eq("Hello"), eq("回复"));
    }

    @Test
    void sendMessageStream_workflowStreamsRuntimeThinkingAndFinalOutput() {
        ChatSession workflowSession = new ChatSession();
        workflowSession.setId(2L);
        workflowSession.setUserId(100L);
        workflowSession.setMode("WORKFLOW");
        workflowSession.setWorkflowId(8L);
        workflowSession.setStatus("ACTIVE");
        workflowSession.setDeleted(0);

        when(sessionMapper.insert(any(ChatSession.class))).thenAnswer(inv -> {
            inv.getArgument(0, ChatSession.class).setId(2L);
            return 1;
        });
        when(sessionMapper.selectById(2L)).thenReturn(workflowSession);
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, ChatMessage.class).setId(20L);
            return 1;
        });
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(runtimeExecutionService.runWorkflowFromChat(8L, 100L, 2L, "你好啊"))
                .thenReturn(reactor.core.publisher.Flux.just(
                        ExecutionEvent.builder()
                                .type("NODE_STARTED")
                                .nodeId("start-1")
                                .status("RUNNING")
                                .input(Map.of("ccc", "你好啊"))
                                .build(),
                        ExecutionEvent.builder()
                                .type("NODE_COMPLETED")
                                .nodeId("skill-1")
                                .status("SUCCESS")
                                .output(Map.of("text", "最终回答"))
                                .build(),
                        ExecutionEvent.builder()
                                .type("EXECUTION_COMPLETED")
                                .status("SUCCESS")
                                .build()));

        ChatRequest request = new ChatRequest();
        request.setMessage("你好啊");
        request.setWorkflowId(8L);

        List<com.superprogrammer.chat.dto.StreamEvent> events =
                chatSessionService.sendMessageStream(100L, request).collectList().block();

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(event ->
                "THINKING".equals(event.getType()) && event.getContent().contains("start-1")));
        assertTrue(events.stream().anyMatch(event ->
                "CHUNK".equals(event.getType()) && event.getContent().equals("最终回答")));
        verify(runtimeExecutionService).runWorkflowFromChat(8L, 100L, 2L, "你好啊");
        verify(orchestrationEngine, never()).executeStream(any(), any());
        verify(messageMapper, atLeastOnce()).insert(argThat(message ->
                "ASSISTANT".equals(message.getRole()) && "最终回答".equals(message.getContent())));
    }
}
