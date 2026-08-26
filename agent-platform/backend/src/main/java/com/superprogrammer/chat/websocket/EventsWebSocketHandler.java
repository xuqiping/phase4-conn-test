package com.superprogrammer.chat.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * /ws/events 通道（7x-3 · 计划 E2）：按 userId 索引的可广播下行通道。
 *
 * <p>与 /ws/chat 独立 Handler 互不共享 session；鉴权同款 {@code WebSocketAuthInterceptor}
 * （握手校验一次 token，uid 放 attributes）。上行无业务语义——连接只作下行推送靶。
 *
 * <p>连接管理（plan 坑点③④）：
 * <ul>
 *   <li>uid→Set&lt;Session&gt; 用 {@code ConcurrentHashMap.newKeySet()}（多端登录同 uid 全收）；</li>
 *   <li>每个 session 包 {@link org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator}
 *       ——串行化并发 send、慢连接（发送超 5s / 缓冲超 256KB）抛异常即剔除，一个坏连接不炸整个循环；</li>
 *   <li>{@code @Scheduled} 每 30s 服务端 ping（浏览器自动 pong 保活，僵尸连接靠 ping 失败剔除）；</li>
 *   <li>close/transportError 双清（Set + session→uid 反查表）。</li>
 * </ul>
 *
 * <p>推送失败只影响该连接（移除+close），不影响其他连接与业务——DB 是真相源，推送是显示层。
 * 计数器（推送成功/丢弃）仿 UsageCollector 风格，供运维日志观测。
 */
@Slf4j
@Component
public class EventsWebSocketHandler extends TextWebSocketHandler {

    /** 发送超时 5s / 单连接缓冲上限 256KB——超限 decorator 抛异常，剔除该连接。 */
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_LIMIT = 256 * 1024;

    /** uid → 该用户的全部活跃连接（多端登录）。 */
    private final Map<Long, Set<WebSocketSession>> byUserId = new ConcurrentHashMap<>();
    /** sessionId → uid 反查（close 时无需再读 attributes——close 事件 attributes 可能已清）。 */
    private final Map<String, Long> sessionUid = new ConcurrentHashMap<>();

    private final AtomicLong pushedCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long uid = getUserId(session);
        if (uid == null) {
            // 防御：拦截器已保证 uid，走到这里=配置漏挂，直接拒绝
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        WebSocketSession decorated = decorate(session);
        byUserId.computeIfAbsent(uid, k -> ConcurrentHashMap.newKeySet()).add(decorated);
        sessionUid.put(session.getId(), uid);
        log.info("events WS 连接建立: userId={}, sessionId={}, 该用户连接数={}",
                uid, session.getId(), byUserId.get(uid).size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 上行无业务语义（客户端探活文本任意）；忽略
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(session);
        log.info("events WS 连接关闭: sessionId={}, status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("events WS 传输错误: sessionId={}: {}", session.getId(), exception.toString());
        removeSession(session);
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    /** 心跳：30s 全量 ping（浏览器自动 pong；发送失败=连接已死，剔除）。 */
    @Scheduled(fixedRate = 30_000)
    public void pingAll() {
        PingMessage ping = new PingMessage(ByteBuffer.wrap(new byte[]{1}));
        byUserId.values().forEach(set -> set.forEach(s -> {
            try {
                synchronized (s) {
                    s.sendMessage(ping);
                }
            } catch (Exception e) {
                log.info("events WS ping 失败剔除: sessionId={}: {}", s.getId(), e.toString());
                removeByUid(s);
            }
        }));
    }

    /**
     * 计划 E3 调用：给指定用户全部连接推送 JSON 文本。
     * 单连接异常只剔除该连接（decorated 已串行化，异常=慢/死），不影响其余。
     */
    public void push(Long userId, String jsonText) {
        Set<WebSocketSession> set = byUserId.get(userId);
        if (set == null || set.isEmpty()) {
            return; // 用户无连接（离线/未开页）——静默跳过，事件即弃（重连后全量补拉兜底）
        }
        TextMessage msg = new TextMessage(jsonText);
        for (WebSocketSession s : set) {
            try {
                s.sendMessage(msg);
                pushedCount.incrementAndGet();
            } catch (Exception e) {
                droppedCount.incrementAndGet();
                log.info("events WS 推送失败剔除: userId={} sessionId={}: {}", userId, s.getId(), e.toString());
                set.remove(s);
                closeQuietly(s, CloseStatus.GOING_AWAY);
            }
        }
    }

    /** 运维观测：当前连接用户数 / 总连接数 / 累计推送与丢弃。 */
    public Map<String, Long> stats() {
        long connections = byUserId.values().stream().mapToLong(Set::size).sum();
        return Map.of(
                "onlineUsers", (long) byUserId.size(),
                "connections", connections,
                "pushed", pushedCount.get(),
                "dropped", droppedCount.get());
    }

    private WebSocketSession decorate(WebSocketSession session) {
        return new org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
    }

    private Long getUserId(WebSocketSession session) {
        Object uid = session.getAttributes().get("userId");
        return uid instanceof Long l ? l : null;
    }

    private void removeSession(WebSocketSession session) {
        Long uid = sessionUid.remove(session.getId());
        if (uid != null) {
            Set<WebSocketSession> set = byUserId.get(uid);
            if (set != null) {
                // decorator.getId() 代理原 session id，直接按 id 匹配即可
                set.removeIf(s -> s.getId().equals(session.getId()));
                if (set.isEmpty()) {
                    byUserId.remove(uid, set);
                }
            }
        }
    }

    /** ping 失败时 decorated session 反查剔除（decorator 的 getId 代理原 session）。 */
    private void removeByUid(WebSocketSession decorated) {
        String id = decorated.getId();
        sessionUid.remove(id);
        byUserId.values().forEach(set -> set.removeIf(s -> s.getId().equals(id)));
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
            // 已断开
        }
    }
}
