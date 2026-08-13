// agent-platform/backend/src/main/java/com/superprogrammer/common/security/SecurityEventPublisher.java
package com.superprogrammer.common.security;

import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * 安全事件发布咽喉（11x 加固 · P3-C9）：业务咽喉统一入口，屏蔽 request/ip 解析与异常隔离。
 *
 * <p>clientIp 自动从当前请求解析（ClientIpResolver 可信代理白名单）；非请求线程（worker/定时任务）
 * 无 request → ip=null。发布异常吞——绝不阻业务主链。</p>
 */
@Slf4j
@Component
public class SecurityEventPublisher {

    private final ApplicationEventPublisher eventPublisher;
    private final ClientIpResolver clientIpResolver;

    public SecurityEventPublisher(ApplicationEventPublisher eventPublisher, ClientIpResolver clientIpResolver) {
        this.eventPublisher = eventPublisher;
        this.clientIpResolver = clientIpResolver;
    }

    /** 发非 system 事件（业务咽喉用）。 */
    public void publish(String kind, Long userId, Map<String, Object> payload) {
        try {
            eventPublisher.publishEvent(ApplicationSecurityEvent.of(this, kind, userId, currentIp(), payload));
        } catch (Exception e) {
            log.warn("安全事件发布失败(已吞) kind={} : {}", kind, e.getMessage());
        }
    }

    private String currentIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            return clientIpResolver.resolve(request);
        } catch (Exception e) {
            return null;
        }
    }
}
