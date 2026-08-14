// agent-platform/backend/src/main/java/com/superprogrammer/common/security/rule/TokenReuseRule.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.system.service.SystemSettingService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Token 盗号/共享（11x 加固 · P3-C9）：同账号 10min 内 ≥3 个不同 IP 登录成功 →
 * TOKEN_REUSE MEDIUM（只告警 admin 核查；精确 refresh 重放由 refresh 轮转+DB status 兜底）。
 * 数据源：Redis {@code sec:rule:ips:{uid}} 集合（TTL 10min）。
 *
 * <p>安全体系 S5 · SEC-FR-004+（A4 旋转）：新增直发通道——AuthService 刷新路径检出
 * 「已被旋转换走的 refresh 再次使用」（黑名单标记="rotated"）→ KIND_TOKEN_REUSE 直达本规则，
 * 无需滑窗累积（旧票本不该再出现，一次即信号）。payload: jti。</p>
 */
@Component
public class TokenReuseRule extends RuleRedisSupport implements SecurityRule {

    private static final long WINDOW_SECONDS = 600;
    private static final long DEFAULT_THRESHOLD = 3;
    public static final String KEY_THRESHOLD = "security.rule.token.ips";

    public TokenReuseRule(StringRedisTemplate redisTemplate, SystemSettingService systemSettingService) {
        super(redisTemplate, systemSettingService);
    }

    @Override
    public boolean supports(String kind) {
        return ApplicationSecurityEvent.KIND_LOGIN_SUCCESS.equals(kind)
                || ApplicationSecurityEvent.KIND_TOKEN_REUSE.equals(kind);
    }

    @Override
    public Verdict evaluate(ApplicationSecurityEvent event) {
        // S5 A4 旋转重放：直发事件（请求已在 AuthService 被拒），落事件+告警即可
        if (ApplicationSecurityEvent.KIND_TOKEN_REUSE.equals(event.getKind())) {
            String detail = "{\"replay\":true,\"jti\":\"" + event.getPayload().getOrDefault("jti", "") + "\"}";
            return new Verdict(SecurityEventTypes.TOKEN_REUSE, SecurityEventTypes.SEV_MEDIUM,
                    event.getUserId(), event.getClientIp(), detail, SecurityEventTypes.ACT_NONE);
        }
        Long userId = event.getUserId();
        String ip = event.getClientIp();
        if (userId == null || ip == null || ip.isBlank()) {
            return null;
        }
        long distinctIps = saddSize("sec:rule:ips:" + userId, ip, WINDOW_SECONDS);
        long threshold = threshold(KEY_THRESHOLD, DEFAULT_THRESHOLD);
        if (distinctIps < 0 || distinctIps < threshold) {
            return null;
        }
        String detail = "{\"distinctIps\":" + distinctIps + ",\"windowSec\":" + WINDOW_SECONDS + "}";
        return new Verdict(SecurityEventTypes.TOKEN_REUSE, SecurityEventTypes.SEV_MEDIUM,
                userId, ip, detail, SecurityEventTypes.ACT_NONE);
    }
}
