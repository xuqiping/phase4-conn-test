// agent-platform/backend/src/main/java/com/superprogrammer/common/security/event/ApplicationSecurityEvent.java
package com.superprogrammer.common.security.event;

import org.springframework.context.ApplicationEvent;

import java.util.Map;

/**
 * 应用安全事件（11x 加固 · P3-C8）：业务咽喉 publishEvent 发，SecurityMonitorWorker 异步求值。
 *
 * <p>单类 + kind 字符串（15 检测码语义在规则侧 SecurityEventTypes），payload 携带业务上下文
 * （如 DataExfil: resourceType/count；PointsUsage: delta/balance；ChatMessage: content）。
 *
 * <p><b>system=true 防循环</b>：AutoResponder 处置（封号/封 IP）派生的事件标记 system，
 * Worker 对 system 事件不再求值（防「规则→处置→新事件→再触发规则」递归）。</p>
 */
public class ApplicationSecurityEvent extends ApplicationEvent {

    // ---- 事件类别（kind：业务咽喉语义，非检测规则码） ----
    /** 下载/导出（payload: resourceType, count）。 */
    public static final String KIND_DATA_EXFIL = "DATA_EXFIL";
    /** 积分扣减（payload: delta, balanceAfter）。 */
    public static final String KIND_POINTS_USAGE = "POINTS_USAGE";
    /** 媒体生成提交（payload: estimatedCostFen, windowCostFen, taskCount）。 */
    public static final String KIND_MEDIA_SUBMIT = "MEDIA_SUBMIT";
    /** chat 消息（payload: content）。 */
    public static final String KIND_CHAT_MESSAGE = "CHAT_MESSAGE";
    /** 鉴权拒绝 403（payload: uri, method）。 */
    public static final String KIND_AUTHZ_DENIED = "AUTHZ_DENIED";
    /** 登录成功（payload: geo）。 */
    public static final String KIND_LOGIN_SUCCESS = "LOGIN_SUCCESS";
    /** 特权/敏感配置变更（payload: action, targetType, targetId）。 */
    public static final String KIND_PRIVILEGE_CHANGE = "PRIVILEGE_CHANGE";
    /** KB 文档入库检出注入特征（安全体系 S3 · SEC-FR-051；payload: docId, kbId, hit）。 */
    public static final String KIND_KB_INJECTION = "KB_INJECTION";
    /** LLM 输出含静态 system prompt 指纹（安全体系 S3 · SEC-FR-053；payload: channel, chars——不带原文）。 */
    public static final String KIND_PROMPT_LEAK = "PROMPT_LEAK";

    private final String kind;
    private final Long userId;
    private final String clientIp;
    private final Map<String, Object> payload;
    /** true=系统处置派生（Worker 跳过求值防递归）。 */
    private final boolean system;

    public ApplicationSecurityEvent(Object source, String kind, Long userId, String clientIp,
                                    Map<String, Object> payload, boolean system) {
        super(source);
        this.kind = kind;
        this.userId = userId;
        this.clientIp = clientIp;
        this.payload = payload == null ? Map.of() : payload;
        this.system = system;
    }

    /** 业务咽喉用：非 system 事件。 */
    public static ApplicationSecurityEvent of(Object source, String kind, Long userId, String clientIp,
                                              Map<String, Object> payload) {
        return new ApplicationSecurityEvent(source, kind, userId, clientIp, payload, false);
    }

    /** AutoResponder 用：system 派生事件（Worker 跳过）。 */
    public static ApplicationSecurityEvent system(Object source, String kind, Long userId, String clientIp,
                                                  Map<String, Object> payload) {
        return new ApplicationSecurityEvent(source, kind, userId, clientIp, payload, true);
    }

    public String getKind() {
        return kind;
    }

    public Long getUserId() {
        return userId;
    }

    public String getClientIp() {
        return clientIp;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public boolean isSystem() {
        return system;
    }
}
