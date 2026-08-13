// agent-platform/backend/src/main/java/com/superprogrammer/common/security/rule/SecurityRule.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.common.security.event.ApplicationSecurityEvent;

/**
 * 安全冷规则接口（11x 加固 · P3-C9）：各规则 impl 本接口，Worker 遍历求值。
 *
 * <p>实现约定：
 * <ul>
 *   <li>纯逻辑易测——计数走注入的 StringRedisTemplate，不直接查业务表；</li>
 *   <li>不命中返回 null；命中返回 Verdict（severity/eventType/detail/autoAction）；</li>
 *   <li>任何异常内部吞掉返 null（单规则故障不拖垮 Worker 循环）。</li>
 * </ul>
 */
public interface SecurityRule {

    /** 本规则关心的事件类别（ApplicationSecurityEvent.KIND_*）。 */
    boolean supports(String kind);

    /** 求值：不命中 → null；命中 → Verdict。 */
    Verdict evaluate(ApplicationSecurityEvent event);

    /** 判定结果。 */
    record Verdict(String eventType, String severity, Long userId, String clientIp,
                   String detailJson, String autoAction) {
    }
}
