package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.ChatRequest;
import com.superprogrammer.chat.dto.ChatResponse;
import com.superprogrammer.chat.dto.ChatTargetVO;
import com.superprogrammer.chat.dto.SessionVO;
import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.chat.service.ChatSessionService;
import com.superprogrammer.chat.service.ChatTargetService;
import com.superprogrammer.common.result.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock private ChatSessionService chatSessionService;
    @Mock private ChatTargetService chatTargetService;
    // 9x#11 聊天队列（5a38b6e5）后控制器新增的三依赖：不 mock 则 @InjectMocks 注入 null，流式端点 NPE
    @Mock private com.superprogrammer.chat.service.ChatConcurrencyGate chatConcurrencyGate;
    @Mock private com.superprogrammer.chat.service.internal.MemoryAssetUploadService memoryAssetUploadService;
    @Mock private com.superprogrammer.chat.service.internal.MemoryAssetIngestService memoryAssetIngestService;

    @InjectMocks
    private ChatController chatController;

    private SessionVO testSessionVO;
    private ChatResponse testResponse;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(100L, "testuser", List.of()));

        testSessionVO = SessionVO.builder()
                .id(1L)
                .title("Test Session")
                .mode("CHAT")
                .status("ACTIVE")
                .createdAt(OffsetDateTime.now())
                .build();

        testResponse = ChatResponse.builder()
                .sessionId(1L)
                .messageId(10L)
                .content("Hi!")
                .mode("CHAT")
                .build();

        // 流式端点先过并发闸门：默认放行（lenient——非流式用例不碰它）
        lenient().when(chatConcurrencyGate.tryAcquire(any(), any())).thenReturn(true);
    }

    @Test
    void createSession_returnsSessionVO() {
        when(chatSessionService.createSession(eq(100L), any(ChatRequest.class)))
                .thenReturn(testSessionVO);

        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        ResponseEntity<R<SessionVO>> response = chatController.createSession(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(200, response.getBody().getCode());
        assertEquals(1L, response.getBody().getData().getId());
    }

    @Test
    void listSessions_returnsList() {
        when(chatSessionService.listSessions(100L)).thenReturn(List.of(testSessionVO));

        ResponseEntity<R<List<SessionVO>>> response = chatController.listSessions();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().getData().size());
    }

    @Test
    void listTargets_returnsAvailableChatTargets() {
        List<ChatTargetVO> targets = List.of(
                ChatTargetVO.builder().type("NONE").targetKey("none").name("无").available(true).build(),
                ChatTargetVO.builder().type("AGENT").targetKey("agent:10").id(10L).name("CodeBot").available(true).build());
        when(chatTargetService.listTargets(eq(100L), any())).thenReturn(targets);

        ResponseEntity<R<List<ChatTargetVO>>> response = chatController.listTargets();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, response.getBody().getData().size());
        assertEquals("none", response.getBody().getData().get(0).getTargetKey());
        assertEquals("agent:10", response.getBody().getData().get(1).getTargetKey());
    }

    @Test
    void deleteSession_success() {
        doNothing().when(chatSessionService).deleteSession(anyLong(), anyLong());

        ResponseEntity<R<Void>> response = chatController.deleteSession(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void sendMessage_returnsResponse() {
        when(chatSessionService.sendMessage(eq(100L), any(ChatRequest.class)))
                .thenReturn(testResponse);

        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        ResponseEntity<R<ChatResponse>> response = chatController.sendMessage(1L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Hi!", response.getBody().getData().getContent());
    }

    @Test
    void sendMessageNewStream_streamsAllChunks() throws Exception {
        when(chatSessionService.sendMessageStream(eq(100L), any(ChatRequest.class)))
                .thenReturn(Flux.just(
                        StreamEvent.chunk("Hi"),
                        StreamEvent.chunk(" there"),
                        StreamEvent.done()));

        MockMvc mockMvc = standaloneSetup(chatController).build();

        var mvcResult = mockMvc.perform(post("/api/chat/messages/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Hi")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(" there")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DONE")));
    }

    @Test
    void sendMessageNewStream_sendsDoneAfterErrorEvent() throws Exception {
        when(chatSessionService.sendMessageStream(eq(100L), any(ChatRequest.class)))
                .thenReturn(Flux.just(StreamEvent.error("LLM调用失败")));

        MockMvc mockMvc = standaloneSetup(chatController).build();

        var mvcResult = mockMvc.perform(post("/api/chat/messages/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ERROR")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DONE")));
    }

    @Test
    void sendMessageNewStream_sendsDoneWhenStreamingAndFallbackFail() throws Exception {
        when(chatSessionService.sendMessageStream(eq(100L), any(ChatRequest.class)))
                .thenReturn(Flux.error(new RuntimeException("LLM stream failed")));
        when(chatSessionService.sendMessage(eq(100L), any(ChatRequest.class)))
                .thenThrow(new RuntimeException("LLM调用失败"));

        MockMvc mockMvc = standaloneSetup(chatController).build();

        var mvcResult = mockMvc.perform(post("/api/chat/messages/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ERROR")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DONE")));
    }

    @Test
    void sendMessageNewStream_preservesSecurityContextInWorkerThread() throws Exception {
        when(chatSessionService.sendMessageStream(eq(100L), any(ChatRequest.class)))
                .thenAnswer(inv -> SecurityContextHolder.getContext().getAuthentication() == null
                        ? Flux.just(StreamEvent.error("missing security context"), StreamEvent.done())
                        : Flux.just(StreamEvent.chunk("authorized"), StreamEvent.done()));

        MockMvc mockMvc = standaloneSetup(chatController).build();

        var mvcResult = mockMvc.perform(post("/api/chat/messages/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\",\"workflowId\":8}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("authorized")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("missing security context"))));
    }
}
