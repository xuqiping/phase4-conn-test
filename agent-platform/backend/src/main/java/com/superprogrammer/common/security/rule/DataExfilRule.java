// agent-platform/backend/src/main/java/com/superprogrammer/common/security/rule/DataExfilRule.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.system.service.SystemSettingService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据外带（11x 加固 · P3-C9）：单用户 10min 下载/导出累计 ≥500 条 → DATA_EXFIL HIGH。
 * 决策（plan C10 坑）：autoAction=NONE 只告警（移动切 IP 误封风险，不自动封 IP）。
 * 接线点：下载/导出咽喉按批聚合发事件（payload: resourceType, count=本次条数）。
 */
@Component
public class DataExfilRule extends RuleRedisSupport implements SecurityRule {

    private static final long WINDOW_SECONDS = 600;
    private static final long DEFAULT_THRESHOLD = 500;
    public static final String KEY_THRESHOLD = "security.rule.exfil.threshold";

    public DataExfilRule(StringRedisTemplate redisTemplate, SystemSettingService systemSettingService) {
        super(redisTemplate, systemSettingService);
    }

    @Override
    public boolean supports(String kind) {
        return ApplicationSecurityEvent.KIND_DATA_EXFIL.equals(kind);
    }

    @Override
    public Verdict evaluate(ApplicationSecurityEvent event) {
        Long userId = event.getUserId();
        if (userId == null) {
            return null;
        }
        long count = toLong(event.getPayload().get("count"), 1);
        long total = incrWindow("sec:rule:exfil:u:" + userId, count, WINDOW_SECONDS);
        long threshold = threshold(KEY_THRESHOLD, DEFAULT_THRESHOLD);
        if (total < 0 || total < threshold) {
            return null;
        }
        String resourceType = String.valueOf(event.getPayload().getOrDefault("resourceType", ""));
        String detail = "{\"resourceType\":\"" + esc(resourceType) + "\",\"windowCount\":" + total
                + ",\"windowSec\":" + WINDOW_SECONDS + "}";
        return new Verdict(SecurityEventTypes.DATA_EXFIL, SecurityEventTypes.SEV_HIGH,
                userId, event.getClientIp(), detail, SecurityEventTypes.ACT_NONE);
    }

    static long toLong(Object v, long def) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? def : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
