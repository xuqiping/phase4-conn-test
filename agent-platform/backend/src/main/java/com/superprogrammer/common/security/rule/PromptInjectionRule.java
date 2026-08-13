// agent-platform/backend/src/main/java/com/superprogrammer/common/security/rule/PromptInjectionRule.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.common.security.sig.InjectionSignatureLibrary;
import com.superprogrammer.system.service.SystemSettingService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Prompt 注入/越狱（11x 加固 · P3-C9）：chat 消息命中 InjectionSignatureLibrary →
 * PROMPT_INJECTION；首次 MEDIUM（只记录不阻断），同用户 1h 内 ≥3 次屡犯升级 HIGH。
 * autoAction=NONE（只告警；屡犯禁言留后续）。
 * 接线点：ChatSessionService.sendMessage 入口（payload: content）。
 */
@Component
public class PromptInjectionRule extends RuleRedisSupport implements SecurityRule {

    /** 屡犯窗口 1h，默认 3 次升级 HIGH。 */
    private static final long REPEAT_WINDOW_SECONDS = 3600;
    private static final long DEFAULT_REPEAT = 3;
    public static final String KEY_REPEAT = "security.rule.prompt.repeat";

    public PromptInjectionRule(StringRedisTemplate redisTemplate, SystemSettingService systemSettingService) {
        super(redisTemplate, systemSettingService);
    }

    @Override
    public boolean supports(String kind) {
        return ApplicationSecurityEvent.KIND_CHAT_MESSAGE.equals(kind);
    }

    @Override
    public Verdict evaluate(ApplicationSecurityEvent event) {
        Object content = event.getPayload().get("content");
        String matched = InjectionSignatureLibrary.match(content == null ? null : String.valueOf(content));
        if (matched == null) {
            return null;
        }
        String severity = SecurityEventTypes.SEV_MEDIUM;
        long count = -1;
        if (event.getUserId() != null) {
            count = incrWindow("sec:rule:prompt:u:" + event.getUserId(), 1, REPEAT_WINDOW_SECONDS);
            if (count >= threshold(KEY_REPEAT, DEFAULT_REPEAT)) {
                severity = SecurityEventTypes.SEV_HIGH;
            }
        }
        String detail = "{\"matchedSig\":\"" + esc(matched) + "\",\"repeatCount\":" + count + "}";
        return new Verdict(SecurityEventTypes.PROMPT_INJECTION, severity,
                event.getUserId(), event.getClientIp(), detail, SecurityEventTypes.ACT_NONE);
    }
}
