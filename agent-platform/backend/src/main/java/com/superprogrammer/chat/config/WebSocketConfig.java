// agent-platform/backend/src/main/java/com/superprogrammer/chat/config/WebSocketConfig.java
package com.superprogrammer.chat.config;

import com.superprogrammer.chat.websocket.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

/**
 * 安全体系 S5 · SEC-FR-025（C5）：WS 握手 Origin 白名单。
 *
 * <p>原 {@code setAllowedOrigins("*")} 令任意站点页面可发起跨站 WS 握手（CSWSH）：受害者浏览器
 * 携其凭证语境直连 /ws/chat。浏览器 JS 无法伪造 Origin 头，白名单即有效防线。
 *
 * <p>与 {@link com.superprogrammer.common.config.CorsConfig} 同范式共读
 * {@code app.cors.allowed-origins}（逗号分隔精确 Origin）：
 * <ul>
 *   <li><b>已配置（生产）</b>：仅白名单 Origin 放行握手；</li>
 *   <li><b>未配置（dev 默认）</b>：回退 {@code *} + WARN，不打断本地联调（vite 5173 直连）。</li>
 * </ul>
 * 注意：配置非空但拆分后为空（如误填纯逗号）→ 返回空数组 fail-closed（握手全拒，错配立现），
 * 不静默回退 {@code *}。
 *
 * <p>修复VIII B1（VIII-3）：token 鉴权自握手拦截器（原从 URL query/Authorization 头取，
 * 前端只能 {@code ?token=} 进 nginx access log）移至<b>首消息鉴权</b>——
 * 见 {@link WebSocketFirstMessageAuthDecorator}。握手层只留 Origin 白名单；
 * 两个端点均在 SecurityConfig 匿名放行（permitAll），鉴权统一在装饰器内完成。
 */
@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final com.superprogrammer.chat.websocket.EventsWebSocketHandler eventsWebSocketHandler;
    private final WebSocketFirstMessageAuthDecorator firstMessageAuth;

    /** 逗号分隔的精确 Origin 白名单；空 = dev 宽松模式（与 CORS 同源配置，一处配置两处生效）。 */
    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(firstMessageAuth.wrap(chatWebSocketHandler), "/ws/chat")
                .setAllowedOrigins(resolveAllowedOrigins(allowedOrigins));
        // 7x-3（计划 E2）：积分实时推送通道——同款首消息鉴权 + Origin 白名单，独立 Handler 不共享
        // session；鉴权成功后经回调注册广播表（建立时 userId 尚未写入 attributes）
        registry.addHandler(firstMessageAuth.wrap(eventsWebSocketHandler,
                        eventsWebSocketHandler::registerAuthenticated), "/ws/events")
                .setAllowedOrigins(resolveAllowedOrigins(allowedOrigins));
    }

    /** 配置串 → Origin 数组。空/blank → dev 回退 {"*"}+WARN；拆分后为空 → fail-closed 空数组。 */
    static String[] resolveAllowedOrigins(String configured) {
        if (configured == null || configured.isBlank()) {
            log.warn("app.cors.allowed-origins 未配置，WebSocket 握手 Origin 白名单回退为 *（仅限 dev；"
                    + "生产环境必须配置精确 Origin 列表，防跨站 WebSocket 劫持）");
            return new String[]{"*"};
        }
        String[] origins = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        if (origins.length == 0) {
            log.error("app.cors.allowed-origins 配置了非空值但拆分后无有效 Origin（值={}），"
                    + "WebSocket 握手将全部拒绝——请修正配置", configured);
        }
        return origins;
    }
}
