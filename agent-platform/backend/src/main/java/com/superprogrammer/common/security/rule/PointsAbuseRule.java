// agent-platform/backend/src/main/java/com/superprogrammer/common/security/rule/PointsAbuseRule.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.system.service.SystemSettingService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 积分滥用/计费欺诈（11x 加固 · P3-C9）：单用户 10min 积分消耗 ≥10000 → POINTS_ABUSE HIGH，
 * autoAction=ACCOUNT_LOCKED（保护资产，锁号 15min 待 admin 定夺）。
 * 接线点：计费扣点咽喉（payload: delta=本次扣减正数, balanceAfter）。
 */
@Component
public class PointsAbuseRule extends RuleRedisSupport implements SecurityRule {

    private static final long WINDOW_SECONDS = 600;
    private static final long DEFAULT_THRESHOLD = 10_000;
    public static final String KEY_THRESHOLD = "security.rule.points.threshold";

    public PointsAbuseRule(StringRedisTemplate redisTemplate, SystemSettingService systemSettingService) {
        super(redisTemplate, systemSettingService);
    }

    @Override
    public boolean supports(String kind) {
        return ApplicationSecurityEvent.KIND_POINTS_USAGE.equals(kind);
    }

    @Override
    public Verdict evaluate(ApplicationSecurityEvent event) {
        Long userId = event.getUserId();
        if (userId == null) {
            return null;
        }
        long delta = Math.abs(DataExfilRule.toLong(event.getPayload().get("delta"), 0));
        if (delta <= 0) {
            return null;
        }
        long total = incrWindow("sec:rule:points:u:" + userId, delta, WINDOW_SECONDS);
        long threshold = threshold(KEY_THRESHOLD, DEFAULT_THRESHOLD);
        if (total < 0 || total < threshold) {
            return null;
        }
        long balance = DataExfilRule.toLong(event.getPayload().get("balanceAfter"), -1);
        String detail = "{\"windowSpent\":" + total + ",\"balanceAfter\":" + balance
                + ",\"windowSec\":" + WINDOW_SECONDS + "}";
        return new Verdict(SecurityEventTypes.POINTS_ABUSE, SecurityEventTypes.SEV_HIGH,
                userId, event.getClientIp(), detail, SecurityEventTypes.ACT_ACCOUNT_LOCKED);
    }
}
