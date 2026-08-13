// agent-platform/backend/src/main/java/com/superprogrammer/common/security/SecurityMonitorWorker.java
package com.superprogrammer.common.security;

import com.superprogrammer.common.security.alert.AlertRouter;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.common.security.rule.SecurityRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 安全监控 Worker（11x 加固 · P3-C8）：异步消费 ApplicationSecurityEvent，遍历冷规则求值。
 *
 * <p>流程：event → 各 SecurityRule.supports/evaluate → 命中 → SecurityEventService.record
 * （去重窗口内只落 1 行）→ AutoResponder.execute（severity 矩阵处置）。
 * 告警派发（AlertRouter）在 P4-C11 挂接 record 返回值语义。</p>
 *
 * <p>防护：system 事件（AutoResponder 处置派生）跳过求值防递归；单规则异常吞掉继续下一规则；
 * 整体异常吞掉——不阻事件发布方主链。</p>
 */
@Slf4j
@Component
public class SecurityMonitorWorker {

    private final List<SecurityRule> rules;
    private final SecurityEventService securityEventService;
    private final AutoResponder autoResponder;
    private final AlertRouter alertRouter;

    public SecurityMonitorWorker(List<SecurityRule> rules,
                                 SecurityEventService securityEventService,
                                 AutoResponder autoResponder,
                                 AlertRouter alertRouter) {
        this.rules = rules;
        this.securityEventService = securityEventService;
        this.autoResponder = autoResponder;
        this.alertRouter = alertRouter;
    }

    @EventListener
    @Async("securityTaskExecutor")
    public void onApplicationSecurityEvent(ApplicationSecurityEvent event) {
        if (event.isSystem()) {
            return; // 处置派生事件不再求值（防循环触发）
        }
        for (SecurityRule rule : rules) {
            SecurityRule.Verdict verdict = null;
            try {
                if (!rule.supports(event.getKind())) {
                    continue;
                }
                verdict = rule.evaluate(event);
            } catch (Exception e) {
                log.error("安全规则求值异常(跳过本规则) rule={} kind={} : {}",
                        rule.getClass().getSimpleName(), event.getKind(), e.toString());
                continue;
            }
            if (verdict == null) {
                continue;
            }
            // 落库（去重窗口内首次才返 true；重复命中不重复处置）
            boolean recorded = securityEventService.record(verdict.eventType(), verdict.severity(),
                    verdict.userId(), verdict.clientIp(), rule.getClass().getSimpleName(),
                    verdict.detailJson(), verdict.autoAction());
            if (recorded) {
                autoResponder.execute(verdict);
                alertRouter.dispatch(verdict); // P4-C11：仅新记录推钉钉（去重窗口内不重推）
            }
        }
    }
}
