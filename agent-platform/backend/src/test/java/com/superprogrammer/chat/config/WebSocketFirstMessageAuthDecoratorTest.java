// agent-platform/backend/src/test/java/com/superprogrammer/chat/config/WebSocketFirstMessageAuthDecoratorTest.java
package com.superprogrammer.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.auth.security.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 修复VIII B1（VIII-3）：WS 首消息鉴权状态机——
 * 正确 token 放行回 auth_ok / 非 auth 首帧或错 token close(4401) / 未认证业务帧不排队直接断 /
 * 5s 超时 close(4401) / 认证后业务帧透传 / 失败与超时 warn 日志不含 token 内容。
 */
class WebSocketFirstMessageAuthDecoratorTest {

    private JwtUtil jwtUtil;
    private WebSocketHandler delegate;
    private final List<WebSocketSession> registered = new ArrayList<>();
    private WebSocketFirstMessageAuthDecorator decorator;
    private WebSocketHandler gate;
    private WebSocketSession session;

    @BeforeEach
    void setUp() throws Exception {
        jwtUtil = mock(JwtUtil.class);
        delegate = mock(WebSocketHandler.class);
        registered.clear();
        decorator = new WebSocketFirstMessageAuthDecorator(jwtUtil, new ObjectMapper());
        decorator.authTimeoutMs = 60_000; // 默认长窗，超时用例单独调短
        gate = decorator.wrap(delegate, registered::add);
        session = mockSession("s1");
    }

    @AfterEach
    void tearDown() {
        decorator.shutdown();
    }

    private WebSocketSession mockSession(String id) throws IOException {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        when(s.getAttributes()).thenReturn(attrs);
        return s;
    }

    private void establish() throws Exception {
        gate.afterConnectionEstablished(session);
    }

    private void authSucceeds(String token, long userId) throws Exception {
        when(jwtUtil.isTokenValid(token)).thenReturn(true);
        when(jwtUtil.getUserIdFromToken(token)).thenReturn(userId);
    }

    @Test
    void validAuth_marksSessionAcksAndSwallowsAuthFrame() throws Exception {
        establish();
        authSucceeds("tok-ok", 7L);

        gate.handleMessage(session, new TextMessage("{\"type\":\"auth\",\"token\":\"tok-ok\"}"));

        // 标记 + userId 入 attributes（chat/events handler 均读它）
        assertThat(session.getAttributes()).containsEntry("userId", 7L);
        assertThat(WebSocketFirstMessageAuthDecorator.isAuthenticated(session)).isTrue();
        // 回 auth_ok ack
        verify(session).sendMessage(argThat(m -> m instanceof TextMessage t
                && t.getPayload().contains("auth_ok")));
        // events 注册回调触发；auth 帧本身不透传业务 handler
        assertThat(registered).containsExactly(session);
        verify(delegate, never()).handleMessage(any(), any());
    }

    @Test
    void businessFrameBeforeAuth_closes4401WithoutQueueing() throws Exception {
        establish();
        gate.handleMessage(session, new TextMessage("{\"message\":\"hi\"}"));

        verify(session).close(argThat(s -> s.getCode() == 4401));
        verify(delegate, never()).handleMessage(any(), any());
        assertThat(registered).isEmpty();
    }

    @Test
    void invalidToken_closes4401_andNeverLogsToken() throws Exception {
        establish();
        when(jwtUtil.isTokenValid("tok-bad")).thenReturn(false);

        Logger logger = (Logger) LoggerFactory.getLogger(WebSocketFirstMessageAuthDecorator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            gate.handleMessage(session, new TextMessage("{\"type\":\"auth\",\"token\":\"tok-bad\"}"));
        } finally {
            logger.detachAppender(appender);
        }

        verify(session).close(argThat(s -> s.getCode() == 4401));
        verify(delegate, never()).handleMessage(any(), any());
        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
        assertThat(logs).doesNotContain("tok-bad");
    }

    @Test
    void malformedFirstFrame_closes4401() throws Exception {
        establish();
        gate.handleMessage(session, new TextMessage("not-json"));
        verify(session).close(argThat(s -> s.getCode() == 4401));
    }

    @Test
    void authTimeout_closes4401() throws Exception {
        decorator.authTimeoutMs = 30;
        establish();
        // 等超时任务触发（未收到 auth 帧）
        Thread.sleep(200);
        verify(session).close(argThat(s -> s.getCode() == 4401));
        verify(delegate, never()).handleMessage(any(), any());
    }

    @Test
    void afterAuth_businessFramesDelegate() throws Exception {
        establish();
        authSucceeds("tok-ok", 7L);
        gate.handleMessage(session, new TextMessage("{\"type\":\"auth\",\"token\":\"tok-ok\"}"));

        WebSocketMessage<?> business = new TextMessage("{\"message\":\"hello\"}");
        gate.handleMessage(session, business);

        verify(delegate).handleMessage(session, business);
    }

    @Test
    void authWithoutTimeoutFiring_connectionStaysOpen() throws Exception {
        // 鉴权成功后超时任务必须被撤销——不发 auth 的 close 不触发
        decorator.authTimeoutMs = 50;
        establish();
        authSucceeds("tok-ok", 9L);
        gate.handleMessage(session, new TextMessage("{\"type\":\"auth\",\"token\":\"tok-ok\"}"));
        Thread.sleep(200);
        verify(session, never()).close(any(CloseStatus.class));
        assertThat(WebSocketFirstMessageAuthDecorator.isAuthenticated(session)).isTrue();
    }
}
