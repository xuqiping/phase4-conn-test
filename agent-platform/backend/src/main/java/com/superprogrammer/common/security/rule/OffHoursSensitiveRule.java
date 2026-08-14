// agent-platform/backend/src/main/java/com/superprogrammer/common/security/rule/OffHoursSensitiveRule.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.system.service.SystemSettingService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/**
 * 凌晨敏感操作（11x 加固 · P3-C9）：0-6 点特权/敏感配置变更 → OFF_HOURS_SENSITIVE LOW。
 * 纯时间判定，无状态。
 */
@Component
public class OffHoursSensitiveRule extends RuleRedisSupport implements SecurityRule {

    private static final LocalTime OFF_START = LocalTime.MIDNIGHT;      // 00:00
    private static final LocalTime OFF_END = LocalTime.of(6, 0);        // 06:00

    public OffHoursSensitiveRule(StringRedisTemplate redisTemplate, SystemSettingService systemSettingService) {
        super(redisTemplate, systemSettingService);
    }

    @Override
    public boolean supports(String kind) {
        return ApplicationSecurityEvent.KIND_PRIVILEGE_CHANGE.equals(kind);
    }

    @Override
    public Verdict evaluate(ApplicationSecurityEvent event) {
        LocalTime now = LocalTime.now();
        if (!(now.isAfter(OFF_START) && now.isBefore(OFF_END))) {
            return null;
        }
        return new Verdict(SecurityEventTypes.OFF_HOURS_SENSITIVE, SecurityEventTypes.SEV_LOW,
                event.getUserId(), event.getClientIp(), buildDetail(event.getPayload(), now),
                SecurityEventTypes.ACT_NONE);
    }

    /**
     * 13x-1：detail 保留 targetType/targetId（此前被丢弃导致详情页看不到操作对象）。
     * 抽静态纯函数便于确定性单测（evaluate 依赖系统时钟）。
     */
    static String buildDetail(java.util.Map<String, Object> payload, LocalTime at) {
        String action = String.valueOf(payload.getOrDefault("action", ""));
        String targetType = String.valueOf(payload.getOrDefault("targetType", ""));
        String targetId = String.valueOf(payload.getOrDefault("targetId", ""));
        return "{\"action\":\"" + esc(action) + "\",\"targetType\":\"" + esc(targetType)
                + "\",\"targetId\":\"" + esc(targetId) + "\",\"at\":\"" + at + "\"}";
    }
}
