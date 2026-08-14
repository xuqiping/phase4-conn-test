// agent-platform/backend/src/main/java/com/superprogrammer/common/security/rule/KbInjectionRule.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.superprogrammer.system.service.SystemSettingService;

/**
 * KB 文档入库注入检出（安全体系 S3 · SEC-FR-051 / LLM01 入库面 + LLM04 投毒）：
 * 文档解析链命中 InjectionSignatureLibrary.matchFull → 文档已置 QUARANTINED 隔离，
 * 本规则只负责落事件+告警（隔离动作在 DocumentParserService 同步完成，规则侧不重复处置）。
 * 恒 HIGH（知识库投毒是平台级风险），autoAction=NONE（复核归管理员，见 unquarantine 端点）。
 * 接线点：DocumentParserService.parse 注入扫描命中处（payload: docId, kbId, hit）。
 */
@Component
public class KbInjectionRule extends RuleRedisSupport implements SecurityRule {

    public KbInjectionRule(StringRedisTemplate redisTemplate, SystemSettingService systemSettingService) {
        super(redisTemplate, systemSettingService);
    }

    @Override
    public boolean supports(String kind) {
        return ApplicationSecurityEvent.KIND_KB_INJECTION.equals(kind);
    }

    @Override
    public Verdict evaluate(ApplicationSecurityEvent event) {
        String detail = "{\"docId\":" + event.getPayload().getOrDefault("docId", null)
                + ",\"kbId\":" + event.getPayload().getOrDefault("kbId", null)
                + ",\"matchedSig\":\"" + esc(String.valueOf(event.getPayload().getOrDefault("hit", ""))) + "\"}";
        return new Verdict(SecurityEventTypes.KB_INJECTION, SecurityEventTypes.SEV_HIGH,
                event.getUserId(), event.getClientIp(), detail, SecurityEventTypes.ACT_NONE);
    }
}
