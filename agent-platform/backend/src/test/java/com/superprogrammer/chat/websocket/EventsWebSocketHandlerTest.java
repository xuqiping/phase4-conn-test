package com.superprogrammer.chat.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划 E2：/ws/events 通道单测——同 uid 多连接全收、坏连接不炸循环、close 后索引清零、
 * 无 uid 拒连。鉴权拒绝（无 token）由 WebSocketAuthInterceptor 保证（同 /ws/chat 拦截器）。
 */
class EventsWebSocketHandlerTest {

    private final EventsWebSocketHandler handler = new EventsWebSocketHandler();

    private WebSocketSession mockSession(Long uid) throws IOException {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn("s-" + System.nanoTime() + "-" + (int) (Math.random() * 1_000_000));
        when(s.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        if (uid != null) {
            attrs.put("userId", uid);
        }
        when(s.getAttributes()).thenReturn(attrs);
        return s;
    }

    @Test
    void sameUserTwoConnections_bothReceivePush() throws Exception {
        WebSocketSession a = mockSession(5L);
        WebSocketSession b = mockSession(5L);
        handler.afterConnectionEstablished(a);
        handler.afterConnectionEstablished(b);

        handler.push(5L, "{\"type\":\"points.changed\"}");

        verify(a).sendMessage(argThat(m -> m instanceof TextMessage t
                && t.getPayload().contains("points.changed")));
        verify(b).sendMessage(argThat(m -> m instanceof TextMessage t
                && t.getPayload().contains("points.changed")));
        assertThat(handler.stats()).containsEntry("onlineUsers", 1L).containsEntry("connections", 2L);
    }

    @Test
    void offlineUser_pushIsNoop() throws Exception {
        handler.push(42L, "{}");
        assertThat(handler.stats()).containsEntry("pushed", 0L);
    }

    @Test
    void brokenConnection_removedWithoutKillingLoop() throws Exception {
        WebSocketSession bad = mockSession(7L);
        WebSocketSession good = mockSession(7L);
        doThrow(new IOException("broken pipe")).when(bad).sendMessage(any());
        handler.afterConnectionEstablished(bad);
        handler.afterConnectionEstablished(good);

        handler.push(7L, "{\"delta\":1}");

        verify(good).sendMessage(any(TextMessage.class));
        verify(bad).close(any(CloseStatus.class));
        assertThat(handler.stats()).containsEntry("connections", 1L).containsEntry("dropped", 1L);

        // 再推一次：bad 已剔除，good 仍收到；bad 的 close 仍只有第一次剔除那一次
        handler.push(7L, "{\"delta\":2}");
        verify(bad, org.mockito.Mockito.times(1)).close(any());
    }

    @Test
    void afterConnectionClosed_indexCleared() throws Exception {
        WebSocketSession s = mockSession(9L);
        handler.afterConnectionEstablished(s);

        handler.afterConnectionClosed(s, CloseStatus.NORMAL);

        handler.push(9L, "{}");
        verify(s, never()).sendMessage(any(TextMessage.class));
        assertThat(handler.stats()).containsEntry("onlineUsers", 0L).containsEntry("connections", 0L);
    }

    @Test
    void missingUserId_rejected() throws Exception {
        WebSocketSession s = mockSession(null);
        handler.afterConnectionEstablished(s);
        verify(s).close(CloseStatus.POLICY_VIOLATION);
        assertThat(handler.stats()).containsEntry("connections", 0L);
    }

    /**
     * 计划 E7 轻量压测（单测级）：5 用户 × 每人 10 连接 = 50 并发连接，
     * 每连接 100 次事件推送——无异常抛出、无连接泄漏、计数守恒。
     */
    @Test
    void stress_50Connections_100EventsPerUser_noLeakNoCrash() throws Exception {
        java.util.List<WebSocketSession> all = new java.util.ArrayList<>();
        for (long uid = 1; uid <= 5; uid++) {
            for (int i = 0; i < 10; i++) {
                WebSocketSession s = mockSession(uid);
                all.add(s);
                handler.afterConnectionEstablished(s);
            }
        }
        assertThat(handler.stats()).containsEntry("onlineUsers", 5L).containsEntry("connections", 50L);

        for (int round = 0; round < 100; round++) {
            for (long uid = 1; uid <= 5; uid++) {
                handler.push(uid, "{\"round\":" + round + "}");
            }
        }

        // 每连接应收满 100 帧（mock sendMessage 无异常 → 无剔除）
        for (WebSocketSession s : all) {
            verify(s, org.mockito.Mockito.times(100)).sendMessage(any(TextMessage.class));
        }
        assertThat(handler.stats())
                .containsEntry("connections", 50L)
                .containsEntry("dropped", 0L)
                .containsEntry("pushed", 5000L); // 5 用户 × 100 轮 × 每用户 10 连接

        // 全部关闭后索引清零
        all.forEach(s -> handler.afterConnectionClosed(s, CloseStatus.NORMAL));
        assertThat(handler.stats()).containsEntry("onlineUsers", 0L).containsEntry("connections", 0L);
    }
}
