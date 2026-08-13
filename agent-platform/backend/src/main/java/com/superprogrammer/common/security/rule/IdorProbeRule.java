// agent-platform/backend/src/main/java/com/superprogrammer/common/security/rule/IdorProbeRule.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.system.service.SystemSettingService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 越权探测（11x 加固 · P3-C9）：同用户/IP 短窗 403 累计 ≥10 → IDOR_PROBE MEDIUM（只告警）。
 * 接线点：GlobalExceptionHandler 403 分支发 KIND_AUTHZ_DENIED（payload: uri, method）。
 */
@Component
public class IdorProbeRule extends RuleRedisSupport implements SecurityRule {

    /** 窗口 5min，默认阈值 10 次。 */
    private static final long WINDOW_SECONDS = 300;
    private static final long DEFAULT_THRESHOLD = 10;
    public static final String KEY_THRESHOLD = "security.rule.idor.threshold";

    public IdorProbeRule(StringRedisTemplate redisTemplate, SystemSettingService systemSettingService) {
        super(redisTemplate, systemSettingService);
    }

    @Override
    public boolean supports(String kind) {
        return ApplicationSecurityEvent.KIND_AUTHZ_DENIED.equals(kind);
    }

    @Override
    public Verdict evaluate(ApplicationSecurityEvent event) {
        long threshold = threshold(KEY_THRESHOLD, DEFAULT_THRESHOLD);
        String uri = String.valueOf(event.getPayload().getOrDefault("uri", ""));
        // 用户维度优先；匿名（userId=null）退 IP 维度
        String key = event.getUserId() != null
                ? "sec:rule:403:u:" + event.getUserId()
                : "sec:rule:403:ip:" + event.getClientIp();
        long count = incrWindow(key, 1, WINDOW_SECONDS);
        if (count < 0 || count < threshold) {
            return null;
        }
        String detail = "{\"count\":" + count + ",\"windowSec\":" + WINDOW_SECONDS
                + ",\"lastUri\":\"" + esc(uri) + "\"}";
        return new Verdict(SecurityEventTypes.IDOR_PROBE, SecurityEventTypes.SEV_MEDIUM,
                event.getUserId(), event.getClientIp(), detail, SecurityEventTypes.ACT_NONE);
    }
}
