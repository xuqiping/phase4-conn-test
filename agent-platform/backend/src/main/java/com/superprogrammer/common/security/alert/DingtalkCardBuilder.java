// agent-platform/backend/src/main/java/com/superprogrammer/common/security/alert/DingtalkCardBuilder.java
package com.superprogrammer.common.security.alert;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.rule.SecurityRule;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 钉钉 markdown 卡片构建器（11x 加固 · P4-C11）：Verdict → 中文标题 + 正文（含 traceId + 处置深链）。
 */
public final class DingtalkCardBuilder {

    private DingtalkCardBuilder() {
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 事件类型中文映射（AuditLabelDictionary 同款字典思路）。 */
    private static final Map<String, String> TYPE_CN = Map.ofEntries(
            Map.entry(SecurityEventTypes.LOGIN_BRUTE_FORCE, "登录暴破"),
            Map.entry(SecurityEventTypes.CREDENTIAL_STUFFING, "撞库攻击"),
            Map.entry(SecurityEventTypes.SQLI_PROBE, "SQL注入探测"),
            Map.entry(SecurityEventTypes.XSS_PROBE, "XSS探测"),
            Map.entry(SecurityEventTypes.PATH_PROBE, "路径穿越探测"),
            Map.entry(SecurityEventTypes.RATE_BURST, "频率突发"),
            Map.entry(SecurityEventTypes.IP_BLOCKED_HIT, "黑名单IP命中"),
            Map.entry(SecurityEventTypes.IDOR_PROBE, "越权探测"),
            Map.entry(SecurityEventTypes.IMPOSSIBLE_TRAVEL, "异地登录"),
            Map.entry(SecurityEventTypes.OFF_HOURS_SENSITIVE, "凌晨敏感操作"),
            Map.entry(SecurityEventTypes.DATA_EXFIL, "数据外带"),
            Map.entry(SecurityEventTypes.POINTS_ABUSE, "积分滥用"),
            Map.entry(SecurityEventTypes.MEDIA_ABUSE, "媒体滥用"),
            Map.entry(SecurityEventTypes.PROMPT_INJECTION, "Prompt注入"),
            Map.entry(SecurityEventTypes.KB_INJECTION, "KB文档注入隔离"),
            Map.entry(SecurityEventTypes.TOKEN_REUSE, "Token盗号疑似"),
            Map.entry(SecurityEventTypes.PRIVILEGE_CHANGE, "特权变更"));

    private static final Map<String, String> SEV_CN = Map.of(
            SecurityEventTypes.SEV_CRITICAL, "危急",
            SecurityEventTypes.SEV_HIGH, "高危",
            SecurityEventTypes.SEV_MEDIUM, "中危",
            SecurityEventTypes.SEV_LOW, "低危");

    /** 构建卡片标题（钉钉会话列表显示）。 */
    public static String title(SecurityRule.Verdict v) {
        return "[安全告警·" + SEV_CN.getOrDefault(v.severity(), v.severity()) + "] "
                + TYPE_CN.getOrDefault(v.eventType(), v.eventType());
    }

    /** 构建 markdown 正文。traceId 取 MDC（Worker 线程经 TaskDecorator 透传）。 */
    public static String text(SecurityRule.Verdict v) {
        String traceId = MDC.get("traceId");
        StringBuilder sb = new StringBuilder(256);
        sb.append("### ").append(title(v)).append('\n');
        sb.append("- **类型**：").append(v.eventType()).append('\n');
        sb.append("- **严重度**：").append(v.severity()).append('\n');
        sb.append("- **用户**：").append(v.userId() == null ? "-" : v.userId()).append('\n');
        sb.append("- **IP**：").append(v.clientIp() == null ? "-" : v.clientIp()).append('\n');
        sb.append("- **详情**：").append(v.detailJson() == null ? "{}" : v.detailJson()).append('\n');
        sb.append("- **自动处置**：").append(v.autoAction() == null ? "NONE" : v.autoAction()).append('\n');
        sb.append("- **时间**：").append(LocalDateTime.now().format(TS)).append('\n');
        if (traceId != null && !traceId.isBlank()) {
            sb.append("- **traceId**：").append(traceId).append('\n');
        }
        sb.append("\n[进入安全事件中心处置](/admin/security/events)");
        return sb.toString();
    }
}
