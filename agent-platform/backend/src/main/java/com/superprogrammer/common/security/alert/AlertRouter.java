// agent-platform/backend/src/main/java/com/superprogrammer/common/security/alert/AlertRouter.java
package com.superprogrammer.common.security.alert;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.rule.SecurityRule;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 告警路由器（11x 加固 · P4-C11）：安全事件按 severity 分流钉钉。
 *
 * <p>矩阵：CRITICAL/HIGH → 即时卡片；MEDIUM → 凑批 30s 汇总；LOW → 只入库不推。
 * 总闸 {@code security.alert.enabled}（默认 true，关=全部不推只入库）。</p>
 */
@Slf4j
@Component
public class AlertRouter {

    private final SystemSettingService systemSettingService;
    private final WebhookNotifier webhookNotifier;
    private final MediumBatcher mediumBatcher;

    public AlertRouter(SystemSettingService systemSettingService,
                       WebhookNotifier webhookNotifier,
                       MediumBatcher mediumBatcher) {
        this.systemSettingService = systemSettingService;
        this.webhookNotifier = webhookNotifier;
        this.mediumBatcher = mediumBatcher;
    }

    /** 派发一次命中（Worker 在 record 返 true 后调）。任何异常吞——告警故障不阻处置。 */
    public void dispatch(SecurityRule.Verdict verdict) {
        try {
            if (!enabled()) {
                return;
            }
            String severity = verdict.severity();
            if (SecurityEventTypes.SEV_CRITICAL.equals(severity) || SecurityEventTypes.SEV_HIGH.equals(severity)) {
                webhookNotifier.postMarkdown(DingtalkCardBuilder.title(verdict), DingtalkCardBuilder.text(verdict));
            } else if (SecurityEventTypes.SEV_MEDIUM.equals(severity)) {
                mediumBatcher.add(verdict);
            }
            // LOW：只入库不推
        } catch (Exception e) {
            log.error("告警派发异常(已吞) eventType={} : {}", verdict.eventType(), e.toString());
        }
    }

    private boolean enabled() {
        try {
            return systemSettingService.getBoolean(WebhookNotifier.KEY_ALERT_ENABLED, true);
        } catch (Exception e) {
            return true;
        }
    }
}
