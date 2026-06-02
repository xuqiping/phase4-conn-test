package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.ChatRequest;
import com.superprogrammer.chat.dto.ChatResponse;
import com.superprogrammer.chat.dto.SessionVO;
import com.superprogrammer.chat.service.ChatSessionService;
import com.superprogrammer.common.result.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock private ChatSessionService chatSessionService;

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
}
