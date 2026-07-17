package com.superprogrammer.chat.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.dto.ChatRequest;
import com.superprogrammer.chat.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ChatSessionService chatSessionService;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = getUserId(session);
        if (userId != null) {
            sessions.put(session.getId(), session);
            log.info("WebSocket连接建立: userId={}, sessionId={}", userId, session.getId());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long userId = getUserId(session);
        if (userId == null) {
            sendError(session, "未认证");
            return;
        }

        try {
            ChatRequest request = objectMapper.readValue(message.getPayload(), ChatRequest.class);

            // ACK
            sendMessage(session, toJson("ACK", Map.of("timestamp", System.currentTimeMillis())));

            // Stream tokens
            chatSessionService.sendMessageStream(userId, request)
                    .subscribe(
                            evt -> {
                                try {
                                    String type = evt.getType();
                                    if ("CHUNK".equals(type)) {
                                        sendMessage(session, toJson("CHUNK", Map.of("content", evt.getContent())));
                                    } else if ("THINKING".equals(type)) {
                                        sendMessage(session, toJson("THINKING", Map.of("content", evt.getContent())));
                                    } else if ("INPUT_REQUIRED".equals(type)) {
                                        Map<String, Object> payload = new LinkedHashMap<>();
                                        if (evt.getData() != null) {
                                            payload.putAll(evt.getData());
                                        }
                                        if (evt.getSessionId() != null) {
                                            payload.put("sessionId", evt.getSessionId());
                                        }
                                        sendMessage(session, toJson("INPUT_REQUIRED", payload));
                                    } else if ("CITATION".equals(type)) {
                                        // P3：转发 citations（content 为 JSON 串），与 SSE 一致，前端聊天 [n] 回显
                                        sendMessage(session, toJson("CITATION", Map.of("content", evt.getContent())));
                                    }
                                } catch (IOException e) {
                                    log.error("发送流式事件失败: {}", e.getMessage());
                                }
                            },
                            error -> {
                                log.error("流式执行失败: {}", error.getMessage(), error);
                                try {
                                    // 安全审计 #7：error.getMessage() 可能含内部细节，客户端回固定话术。
                                    sendError(session, "执行失败，请稍后重试");
                                } catch (Exception e) {
                                    log.error("发送错误失败: {}", e.getMessage());
                                }
                            },
                            () -> {
                                try {
                                    sendMessage(session, toJson("MESSAGE_COMPLETE", Map.of()));
                                } catch (IOException e) {
                                    log.error("发送MESSAGE_COMPLETE失败: {}", e.getMessage());
                                }
                            }
                    );

        } catch (Exception e) {
            log.error("WebSocket消息处理失败: {}", e.getMessage(), e);
            // 安全审计 #7：e.getMessage() 可能含内部细节，客户端回固定话术。
            sendError(session, "消息处理失败，请稍后重试");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WebSocket连接关闭: sessionId={}, status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket传输错误: sessionId={}", session.getId(), exception);
        sessions.remove(session.getId());
    }

    private Long getUserId(WebSocketSession session) {
        return (Long) session.getAttributes().get("userId");
    }

    private void sendMessage(WebSocketSession session, String payload) throws IOException {
        if (session.isOpen()) {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        }
    }

    private void sendError(WebSocketSession session, String error) {
        try {
            sendMessage(session, toJson("ERROR", Map.of("message", error)));
        } catch (IOException e) {
            log.error("发送WebSocket错误失败: {}", e.getMessage());
        }
    }

    private String toJson(String type, Map<String, Object> payload) throws IOException {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("type", type);
        wrapper.putAll(payload);
        return objectMapper.writeValueAsString(wrapper);
    }
}
