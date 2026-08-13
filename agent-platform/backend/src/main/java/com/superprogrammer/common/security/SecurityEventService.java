// agent-platform/backend/src/main/java/com/superprogrammer/common/security/SecurityEventService.java
package com.superprogrammer.common.security;

import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.common.security.entity.SecurityEvent;
import com.superprogrammer.common.security.mapper.SecurityEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 安全事件记录服务（11x 加固 · P2-C5 轻量版，P3-C8 扩异步总线）。
 *
 * <p>职责：security_events 落库 + 指标 + security 日志通道 WARN。
 *
 * <p><b>告警风暴防护</b>：同 eventType+userId/ip 在去重窗口（5min）内只落 1 行
 * （Redis SET NX 占位，TTL=窗口）。攻击者刷 payload 不会灌爆事件表。
 * Redis 故障 → 不去重直接落库（取证完整 > 风暴风险，与限流降级口径相反——这里落库是写路径，
 * 故障场景 DB 大概率也写不动，catch 吞掉即可，绝不阻断业务主链）。</p>
 */
@Slf4j
@Service
public class SecurityEventService {

    /** 去重键前缀：secevt:dedup:{type}:{user:42|ip:1.2.3.4}。 */
    private static final String DEDUP_PREFIX = "secevt:dedup:";
    /** 去重窗口：5min（对齐钉钉告警去重口径，防告警风暴）。 */
    private static final long DEDUP_WINDOW_SECONDS = 300;

    private final SecurityEventMapper securityEventMapper;
    private final StringRedisTemplate redisTemplate;
    private final BizMetrics bizMetrics;

    public SecurityEventService(SecurityEventMapper securityEventMapper,
                                StringRedisTemplate redisTemplate,
                                BizMetrics bizMetrics) {
        this.securityEventMapper = securityEventMapper;
        this.redisTemplate = redisTemplate;
        this.bizMetrics = bizMetrics;
    }

    /**
     * 记录安全事件（去重窗口内同类同人只落 1 行）。
     *
     * @return true=本次落库（窗口首次）；false=被去重/落库失败（已吞）
     */
    public boolean record(String eventType, String severity, Long userId, String clientIp,
                          String ruleId, String detailJson, String autoAction) {
        if (!markFirstInWindow(eventType, userId, clientIp)) {
            return false; // 去重窗口内已有同类事件，跳过（防风暴）
        }
        try {
            SecurityEvent event = new SecurityEvent();
            event.setEventType(eventType);
            event.setSeverity(severity);
            event.setUserId(userId);
            event.setClientIp(clientIp);
            event.setTraceId(MDC.get("traceId"));
            event.setRuleId(ruleId);
            event.setDetailJson(detailJson);
            event.setAutoAction(autoAction == null ? SecurityEventTypes.ACT_NONE : autoAction);
            event.setHandled(false);
            securityEventMapper.insert(event);
            bizMetrics.securityEventRaised(eventType, severity);
            // security 日志通道（logback com.superprogrammer.common.security → security.log）
            log.warn("安全事件 type={} severity={} userId={} ip={} rule={} autoAction={}",
                    eventType, severity, userId, clientIp, ruleId, event.getAutoAction());
            return true;
        } catch (Exception e) {
            // 落库失败吞掉：安全记录绝不阻断业务主链
            log.error("安全事件落库失败(已吞) type={} userId={} ip={} : {}",
                    eventType, userId, clientIp, e.getMessage());
            return false;
        }
    }

    /** 去重占位：窗口内首次返回 true。Redis 故障 → true（降级照常落库）。 */
    private boolean markFirstInWindow(String eventType, Long userId, String clientIp) {
        try {
            String dimension = userId != null ? "user:" + userId : "ip:" + clientIp;
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent(DEDUP_PREFIX + eventType + ":" + dimension, "1",
                            DEDUP_WINDOW_SECONDS, TimeUnit.SECONDS);
            return !Boolean.FALSE.equals(first);
        } catch (Exception e) {
            log.warn("事件去重占位失败(降级照常落库) type={} : {}", eventType, e.getMessage());
            return true;
        }
    }
}
