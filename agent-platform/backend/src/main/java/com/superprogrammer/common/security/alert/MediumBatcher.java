// agent-platform/backend/src/main/java/com/superprogrammer/common/security/alert/MediumBatcher.java
package com.superprogrammer.common.security.alert;

import com.superprogrammer.common.security.rule.SecurityRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 中危凑批器（11x 加固 · P4-C11）：MEDIUM 事件先入队，每 30s 合并成一条汇总钉钉（防刷群）。
 *
 * <p>重启丢队列可接受——事件已落 security_events 表，前端事件中心可查全量。
 * 队列上限 500（极端洪峰丢弃防 OOM）。</p>
 */
@Slf4j
@Component
public class MediumBatcher {

    /** 队列上限（防极端洪峰 OOM；超出丢弃，事件仍在库）。 */
    static final int MAX_QUEUE = 500;

    private final ConcurrentLinkedQueue<SecurityRule.Verdict> queue = new ConcurrentLinkedQueue<>();
    private final WebhookNotifier webhookNotifier;

    public MediumBatcher(WebhookNotifier webhookNotifier) {
        this.webhookNotifier = webhookNotifier;
    }

    /** 入队一条 MEDIUM 事件（AlertRouter 调）。超上限丢弃 + WARN。 */
    public void add(SecurityRule.Verdict verdict) {
        if (queue.size() >= MAX_QUEUE) {
            log.warn("中危凑批队列满(丢弃,事件已在库) eventType={}", verdict.eventType());
            return;
        }
        queue.add(verdict);
    }

    /** 每 30s 合并推送一批。 */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void flush() {
        List<SecurityRule.Verdict> batch = new ArrayList<>();
        SecurityRule.Verdict v;
        while ((v = queue.poll()) != null) {
            batch.add(v);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder(512);
            sb.append("### [安全告警·中危] 汇总 ").append(batch.size()).append(" 条\n");
            int show = Math.min(batch.size(), 10);
            for (int i = 0; i < show; i++) {
                SecurityRule.Verdict item = batch.get(i);
                sb.append("- ").append(item.eventType())
                        .append(" 用户=").append(item.userId() == null ? "-" : item.userId())
                        .append(" IP=").append(item.clientIp() == null ? "-" : item.clientIp())
                        .append('\n');
            }
            if (batch.size() > show) {
                sb.append("- ... 其余 ").append(batch.size() - show).append(" 条见事件中心\n");
            }
            sb.append("\n[进入安全事件中心处置](/admin/security/events)");
            webhookNotifier.postMarkdown("[安全告警·中危] 汇总 " + batch.size() + " 条", sb.toString());
        } catch (Exception e) {
            log.warn("中危凑批推送异常(已吞) : {}", e.getMessage());
        }
    }
}
