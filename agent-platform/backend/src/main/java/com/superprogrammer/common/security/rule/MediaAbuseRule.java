// agent-platform/backend/src/main/java/com/superprogrammer/common/security/rule/MediaAbuseRule.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.system.service.SystemSettingService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 媒体生成滥用（11x 加固 · P3-C9）：单用户 30min 媒体预估花费 ≥¥100（10000 分）→
 * MEDIA_ABUSE HIGH，autoAction=ACCOUNT_LOCKED（plan 决策：保护资产非封 IP）。
 * 接线点：媒体提交咽喉（payload: estimatedCostFen 累计费用分, taskCount）。
 */
@Component
public class MediaAbuseRule extends RuleRedisSupport implements SecurityRule {

    private static final long WINDOW_SECONDS = 1800;
    /** 默认阈值 ¥100 = 10000 分（计费分=0.01 元）。 */
    private static final long DEFAULT_THRESHOLD_FEN = 10_000;
    public static final String KEY_THRESHOLD = "security.rule.media.thresholdFen";

    public MediaAbuseRule(StringRedisTemplate redisTemplate, SystemSettingService systemSettingService) {
        super(redisTemplate, systemSettingService);
    }

    @Override
    public boolean supports(String kind) {
        return ApplicationSecurityEvent.KIND_MEDIA_SUBMIT.equals(kind);
    }

    @Override
    public Verdict evaluate(ApplicationSecurityEvent event) {
        Long userId = event.getUserId();
        if (userId == null) {
            return null;
        }
        long cost = Math.abs(DataExfilRule.toLong(event.getPayload().get("estimatedCostFen"), 0));
        if (cost <= 0) {
            return null;
        }
        long total = incrWindow("sec:rule:media:u:" + userId, cost, WINDOW_SECONDS);
        long threshold = threshold(KEY_THRESHOLD, DEFAULT_THRESHOLD_FEN);
        if (total < 0 || total < threshold) {
            return null;
        }
        long taskCount = DataExfilRule.toLong(event.getPayload().get("taskCount"), -1);
        String detail = "{\"windowCostFen\":" + total + ",\"taskCount\":" + taskCount
                + ",\"windowSec\":" + WINDOW_SECONDS + "}";
        return new Verdict(SecurityEventTypes.MEDIA_ABUSE, SecurityEventTypes.SEV_HIGH,
                userId, event.getClientIp(), detail, SecurityEventTypes.ACT_ACCOUNT_LOCKED);
    }
}
