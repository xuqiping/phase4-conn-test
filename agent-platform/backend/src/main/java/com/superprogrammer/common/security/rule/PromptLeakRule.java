// agent-platform/backend/src/main/java/com/superprogrammer/common/security/rule/PromptLeakRule.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.superprogrammer.system.service.SystemSettingService;

/**
 * LLM 输出命中静态 system prompt 指纹（安全体系 S3 · SEC-FR-053 / LLM07②）：
 * OutputSanitizer 已在网关咽喉同步遮蔽，本规则只负责落事件+告警（遮蔽动作不在此重复）。
 * 恒 HIGH（system prompt 泄露暴露内部指令/工具面，常为后续注入攻击的侦察步），autoAction=NONE
 * （用户可能合法复述公开介绍文案，封禁误伤大，复核归管理员）。
 * 接线点：OutputSanitizer 同步/流式两路（payload: channel, chars——不带输出原文，PII 红线）。
 */
@Component
public class PromptLeakRule extends RuleRedisSupport implements SecurityRule {

    public PromptLeakRule(StringRedisTemplate redisTemplate, SystemSettingService systemSettingService) {
        super(redisTemplate, systemSettingService);
    }

    @Override
    public boolean supports(String kind) {
        return ApplicationSecurityEvent.KIND_PROMPT_LEAK.equals(kind);
    }

    @Override
    public Verdict evaluate(ApplicationSecurityEvent event) {
        String detail = "{\"channel\":\"" + event.getPayload().getOrDefault("channel", "")
                + "\",\"chars\":" + event.getPayload().getOrDefault("chars", 0) + "}";
        return new Verdict(SecurityEventTypes.PROMPT_LEAK, SecurityEventTypes.SEV_HIGH,
                event.getUserId(), event.getClientIp(), detail, SecurityEventTypes.ACT_NONE);
    }
}
