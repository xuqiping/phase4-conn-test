// agent-platform/backend/src/main/java/com/superprogrammer/common/security/rule/LlmSessionCapRule.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.superprogrammer.system.service.SystemSettingService;

/**
 * 单会话累计 token 达上限（安全体系 S3 · SEC-FR-056 / LLM10 无限消耗）：
 * ChatSessionService 发送前 SUM 检查命中 → 请求已拒（LLM_SESSION_CAP_EXCEEDED 固定话术），
 * 本规则只落事件+告警。MEDIUM（消耗面异常多为脚本刷量，量级未到 HIGH；配合 @RateLimit 三入口
 * 已限频）。autoAction=NONE（是否封禁归管理员复核——正常重度用户也可能触顶）。
 * 接线点：ChatSessionService 同步/流式两路发送前（payload: sessionId, used, cap）。
 */
@Component
public class LlmSessionCapRule extends RuleRedisSupport implements SecurityRule {

    public LlmSessionCapRule(StringRedisTemplate redisTemplate, SystemSettingService systemSettingService) {
        super(redisTemplate, systemSettingService);
    }

    @Override
    public boolean supports(String kind) {
        return ApplicationSecurityEvent.KIND_LLM_SESSION_CAP.equals(kind);
    }

    @Override
    public Verdict evaluate(ApplicationSecurityEvent event) {
        String detail = "{\"sessionId\":" + event.getPayload().getOrDefault("sessionId", null)
                + ",\"used\":" + event.getPayload().getOrDefault("used", 0)
                + ",\"cap\":" + event.getPayload().getOrDefault("cap", 0) + "}";
        return new Verdict(SecurityEventTypes.LLM_SESSION_CAP, SecurityEventTypes.SEV_MEDIUM,
                event.getUserId(), event.getClientIp(), detail, SecurityEventTypes.ACT_NONE);
    }
}
