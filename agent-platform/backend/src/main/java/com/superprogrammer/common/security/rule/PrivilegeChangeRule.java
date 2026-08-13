// agent-platform/backend/src/main/java/com/superprogrammer/common/security/rule/PrivilegeChangeRule.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.system.service.SystemSettingService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 特权/敏感配置变更（11x 加固 · P3-C9）：任意一次即触发 → PRIVILEGE_CHANGE HIGH 实时告警
 * （角色权限改/计费规则改/安全开关改）。autoAction=NONE（钉钉实时推 admin 确认）。
 * 接线点：AuditLogAspect 敏感动作环绕发事件（payload: action, targetType, targetId）。
 */
@Component
public class PrivilegeChangeRule extends RuleRedisSupport implements SecurityRule {

    public PrivilegeChangeRule(StringRedisTemplate redisTemplate, SystemSettingService systemSettingService) {
        super(redisTemplate, systemSettingService);
    }

    @Override
    public boolean supports(String kind) {
        return ApplicationSecurityEvent.KIND_PRIVILEGE_CHANGE.equals(kind);
    }

    @Override
    public Verdict evaluate(ApplicationSecurityEvent event) {
        String action = String.valueOf(event.getPayload().getOrDefault("action", ""));
        String targetType = String.valueOf(event.getPayload().getOrDefault("targetType", ""));
        String targetId = String.valueOf(event.getPayload().getOrDefault("targetId", ""));
        String detail = "{\"action\":\"" + esc(action) + "\",\"targetType\":\"" + esc(targetType)
                + "\",\"targetId\":\"" + esc(targetId) + "\"}";
        return new Verdict(SecurityEventTypes.PRIVILEGE_CHANGE, SecurityEventTypes.SEV_HIGH,
                event.getUserId(), event.getClientIp(), detail, SecurityEventTypes.ACT_NONE);
    }
}
