// agent-platform/backend/src/main/java/com/superprogrammer/chat/config/WebSocketFirstMessageAuthDecorator.java
package com.superprogrammer.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.auth.security.JwtUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 修复VIII B1（VIII-3）：WebSocket 首消息鉴权——token 出 URL。
 *
 * <p>原握手拦截器从 URL query / Authorization 头取 token：浏览器 WebSocket API 无法设自定义头，
 * 前端只能 {@code /ws/chat?token=...}，token 随之进 nginx access log（凭证日志留痕）。
 * 现握手层只留 Origin 白名单（{@link WebSocketConfig#setAllowedOrigins}），
 * 鉴权移到连接建立后的<b>首条客户端消息</b>：
 *
 * <ul>
 *   <li>客户端 open 后发 {@code {"type":"auth","token":"<access token>"}}；</li>
 *   <li>校验通过 → session attributes 写 userId/wsAuthenticated + 回 {@code {"type":"auth_ok"}}，
 *       之后业务帧全部透传被包装 handler（auth 帧被吞不透传）；</li>
 *   <li>首帧非 auth / token 无效 / 5s 超时未认证 / 未认证期发业务帧 →
 *       {@code close(4401)}，不排队不留连（防未认证连接占坑）。</li>
 * </ul>
 *
 * <p>本类是裸 WebSocket（非 STOMP，无 clientInboundChannel），故用 Handler 装饰器实现
 * 「拦首条消息」语义：{@code registry.addHandler(decorator.wrap(handler), path)}。
 * events 通道（{@code EventsWebSocketHandler}）建立时 userId 尚未写入 attributes，
 * 无法在 afterConnectionEstablished 注册广播表——鉴权成功后经 onAuthenticated 回调补注册。
 *
 * <p>安全口径：鉴权失败/超时仅打 warn（含 sessionId/原因，<b>绝不打 token 内容</b>）。
 */
@Slf4j
@Component
public class WebSocketFirstMessageAuthDecorator {

    /** auth 等待窗（ms）：连接建立后此时间内未通过首消息鉴权 → 4401。包内可调（测试用短窗）。 */
    long authTimeoutMs = 5_000;

    /** 应用级关闭码 4401：鉴权失败/超时/未认证先发业务帧（4000-4999 为应用自定义区间）。 */
    static final CloseStatus UNAUTHORIZED = new CloseStatus(4401, "Unauthorized");

    /** session attributes 里「已通过首消息鉴权」的标记键（userId 由校验成功一并写入）。 */
    static final String ATTR_AUTHENTICATED = "wsAuthenticated";

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    /** 超时调度器：daemon 单线程（同一时刻需要计时的未认证连接远少于业务并发）。 */
    private final ScheduledExecutorService authTimeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-auth-timeout");
                t.setDaemon(true);
                return t;
            });

    public WebSocketFirstMessageAuthDecorator(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    /** 包装裸 handler（无鉴权后回调——chat 通道业务帧天然读 attributes.userId，无需注册动作）。 */
    public WebSocketHandler wrap(WebSocketHandler delegate) {
        return new AuthGate(delegate, null);
    }

    /**
     * 包装裸 handler：未认证期间拦帧；首条 auth 帧通过后放行并回调 onAuthenticated
     * （events 通道靠回调注册广播表——established 时 userId 还没进 attributes）。
     */
    public WebSocketHandler wrap(WebSocketHandler delegate, Consumer<WebSocketSession> onAuthenticated) {
        return new AuthGate(delegate, onAuthenticated);
    }

    @PreDestroy
    void shutdown() {
        authTimeoutScheduler.shutdownNow();
    }

    /** 鉴权门闩装饰器：每被 wrap 的 handler 一份（timeoutFutures 只含本端点的未认证连接）。 */
    class AuthGate extends WebSocketHandlerDecorator {

        private final Consumer<WebSocketSession> onAuthenticated;
        /** sessionId → 超时关闭任务（鉴权成功/连接关闭即取消，防泄漏）。 */
        private final Map<String, ScheduledFuture<?>> timeoutFutures = new ConcurrentHashMap<>();

        AuthGate(WebSocketHandler delegate, Consumer<WebSocketSession> onAuthenticated) {
            super(delegate);
            this.onAuthenticated = onAuthenticated;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            super.afterConnectionEstablished(session);
            // 5s 鉴权窗倒计时：到点仍未 auth → 4401（移除任务成功才关，auth 恰好并发通过则不误杀）
            timeoutFutures.put(session.getId(), authTimeoutScheduler.schedule(() -> {
                if (timeoutFutures.remove(session.getId()) == null) {
                    return; // 已被鉴权成功路径取消
                }
                if (session.getAttributes().get(ATTR_AUTHENTICATED) != null) {
                    return; // 双保险：与鉴权成功并发时不误杀
                }
                log.warn("WebSocket 首消息鉴权超时（{}ms 内未收到 auth 帧）: sessionId={}",
                        authTimeoutMs, session.getId());
                closeUnauthorized(session);
            }, authTimeoutMs, TimeUnit.MILLISECONDS));
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
            if (session.getAttributes().get(ATTR_AUTHENTICATED) != null) {
                // 已认证：业务帧全透传（重复 auth 帧也透传，业务 handler 自行消化，零额外解析开销）
                super.handleMessage(session, message);
                return;
            }
            if (!(message instanceof TextMessage text)) {
                log.warn("WebSocket 未认证先发非文本帧，拒绝: sessionId={}", session.getId());
                closeUnauthorized(session);
                return;
            }
            JsonNode root;
            try {
                root = objectMapper.readTree(text.getPayload());
            } catch (Exception e) {
                log.warn("WebSocket 首帧不是合法 JSON，拒绝: sessionId={}", session.getId());
                closeUnauthorized(session);
                return;
            }
            String type = root.path("type").asText(null);
            if (!"auth".equals(type)) {
                // 未认证期间业务帧不排队——直接断（防占坑与乱序投递）
                log.warn("WebSocket 未认证先发业务帧（type={}），拒绝: sessionId={}",
                        StringUtils.hasText(type) ? type : "<blank>", session.getId());
                closeUnauthorized(session);
                return;
            }
            String token = root.path("token").asText(null);
            if (!StringUtils.hasText(token) || !jwtUtil.isTokenValid(token)) {
                // 不打 token 内容——失败原因足够定位，凭证不入日志
                log.warn("WebSocket 首消息鉴权失败（token 缺失或无效）: sessionId={}", session.getId());
                closeUnauthorized(session);
                return;
            }
            Long userId;
            try {
                userId = jwtUtil.getUserIdFromToken(token);
            } catch (Exception e) {
                log.warn("WebSocket 首消息鉴权失败（token 解析用户失败）: sessionId={}", session.getId());
                closeUnauthorized(session);
                return;
            }

            // 通过：标记 + 撤销超时任务 + 回 ack +（events）回调注册广播表。
            // 顺序注意：先回 auth_ok 再回调注册——events 的 ping 广播表只见已注册连接，
            // 注册发生在 ack 之后可避免 ack 与首个 ping 在裸 session 上并发写帧。
            session.getAttributes().put("userId", userId);
            session.getAttributes().put(ATTR_AUTHENTICATED, Boolean.TRUE);
            ScheduledFuture<?> timeout = timeoutFutures.remove(session.getId());
            if (timeout != null) {
                timeout.cancel(false);
            }
            if (!session.isOpen()) {
                // review 竞态修复：5s 超时线程已 close（cancel 与超时触发同时到达）——
                // 不再回 ack/触发注册回调，防把已关闭 session 注册进 events 广播表往死连接投帧
                return;
            }
            try {
                session.sendMessage(new TextMessage("{\"type\":\"auth_ok\"}"));
            } catch (IOException e) {
                log.warn("WebSocket auth_ok 回帧失败: sessionId={}: {}", session.getId(), e.toString());
            }
            if (onAuthenticated != null) {
                onAuthenticated.accept(session);
            }
            // auth 帧本身不透传（chat 通道会把它当 ChatRequest 消化产生噪音 ERROR）
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
            ScheduledFuture<?> timeout = timeoutFutures.remove(session.getId());
            if (timeout != null) {
                timeout.cancel(false);
            }
            super.afterConnectionClosed(session, closeStatus);
        }

        private void closeUnauthorized(WebSocketSession session) {
            timeoutFutures.remove(session.getId());
            try {
                session.close(UNAUTHORIZED);
            } catch (IOException ignored) {
                // 已断开
            }
        }
    }

    static CloseStatus unauthorizedStatus() {
        return UNAUTHORIZED;
    }

    /** 测试断言用：判定 session 是否已通过首消息鉴权。 */
    static boolean isAuthenticated(WebSocketSession session) {
        return Objects.equals(session.getAttributes().get(ATTR_AUTHENTICATED), Boolean.TRUE);
    }
}
