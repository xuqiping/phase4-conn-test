// agent-platform/backend/src/main/java/com/superprogrammer/common/security/rule/ImpossibleTravelRule.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.system.service.SystemSettingService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 异地登录（11x 加固 · P3-C9）：本次登录 geo 与上次不同且间隔 <2h（粗粒度不可能旅行）→
 * IMPOSSIBLE_TRAVEL MEDIUM（只告警）。
 *
 * <p>数据源：Redis {@code sec:rule:lastlogin:{uid}} = "geo|epochSecond"，登录成功时更新
 * （免查 login_attempts 库表）。geo 为空（内网/库缺失）不参与判定；region 粗粒度（省级），
 * 800km/h 精确速度计算无坐标，退化为「不同 region + <2h」近似。</p>
 */
@Component
public class ImpossibleTravelRule extends RuleRedisSupport implements SecurityRule {

    /** 上次登录缓存 7 天（超过视为无参考）。 */
    private static final long LAST_LOGIN_TTL = 7 * 24 * 3600L;
    /** 时间差阈值：2h 内换 region 视为不可能旅行。 */
    private static final long HOURS_THRESHOLD = 2;

    public ImpossibleTravelRule(StringRedisTemplate redisTemplate, SystemSettingService systemSettingService) {
        super(redisTemplate, systemSettingService);
    }

    @Override
    public boolean supports(String kind) {
        return ApplicationSecurityEvent.KIND_LOGIN_SUCCESS.equals(kind);
    }

    @Override
    public Verdict evaluate(ApplicationSecurityEvent event) {
        Long userId = event.getUserId();
        if (userId == null) {
            return null;
        }
        String geo = String.valueOf(event.getPayload().getOrDefault("geo", ""));
        long now = System.currentTimeMillis() / 1000L;
        String key = "sec:rule:lastlogin:" + userId;
        String last = get(key);
        // 先更新本次（无论是否命中），再判定
        set(key, geo + "|" + now, LAST_LOGIN_TTL);
        if (geo.isBlank() || last == null || last.isBlank()) {
            return null;
        }
        // 格式 "geo|epochSecond"；geo 本身含 '|'（ip2region 段分隔）→ 从尾部切最后一个 '|'
        int sep = last.lastIndexOf('|');
        if (sep <= 0 || sep == last.length() - 1) {
            return null;
        }
        String lastGeo = last.substring(0, sep);
        long lastTs;
        try {
            lastTs = Long.parseLong(last.substring(sep + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        long hoursDiff = (now - lastTs) / 3600L;
        if (lastGeo.equals(geo) || hoursDiff >= HOURS_THRESHOLD) {
            return null;
        }
        String detail = "{\"from\":\"" + esc(lastGeo) + "\",\"to\":\"" + esc(geo)
                + "\",\"hoursDiff\":" + hoursDiff + "}";
        return new Verdict(SecurityEventTypes.IMPOSSIBLE_TRAVEL, SecurityEventTypes.SEV_MEDIUM,
                userId, event.getClientIp(), detail, SecurityEventTypes.ACT_NONE);
    }
}
