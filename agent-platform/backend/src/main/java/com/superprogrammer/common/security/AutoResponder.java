// agent-platform/backend/src/main/java/com/superprogrammer/common/security/AutoResponder.java
package com.superprogrammer.common.security;

import com.superprogrammer.common.security.rule.SecurityRule;
import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 分级自动响应器（11x 加固 · P3-C10）：命中安全事件后的自动处置矩阵。
 *
 * <p>处置矩阵（severity → 动作）：
 * <ul>
 *   <li><b>CRITICAL</b>：锁账号（15min）+ 封 IP（24h），双分闸各自独立；</li>
 *   <li><b>HIGH</b>：按 verdict.autoAction 择一——{@code ACT_ACCOUNT_LOCKED} 锁号 /
 *       {@code ACT_IP_BLOCKED} 封 IP 60min / 其他或空 → 只告警；</li>
 *   <li><b>MEDIUM/LOW</b>：只告警（落库+指标），不自动处置。</li>
 * </ul>
 *
 * <p>开关（system_settings，全部热生效）：
 * {@code security.response.auto_enabled}=总闸（默认 true，关则全自动处置停用只告警）；
 * {@code security.response.auto_ip_block} / {@code security.response.auto_account_lock}=分闸（默认 true）。</p>
 *
 * <p>降级：settings/Redis/DB 任一故障 → 吞异常 + WARN，绝不反噬 Worker 主循环。
 * 处置动作本身不产生新 ApplicationSecurityEvent（无需 system 标记防递归——本类不 publish）。</p>
 */
@Slf4j
@Component
public class AutoResponder {

    /** 总闸/分闸 settings key。 */
    public static final String KEY_AUTO_ENABLED = "security.response.auto_enabled";
    public static final String KEY_AUTO_IP_BLOCK = "security.response.auto_ip_block";
    public static final String KEY_AUTO_ACCOUNT_LOCK = "security.response.auto_account_lock";

    /** 锁定时长（分钟）：CRITICAL 与 HIGH 锁号统一 15min（与登录爆破锁定一致）。 */
    public static final int LOCK_MINUTES = 15;
    /** CRITICAL 封 IP 时长（分钟）。 */
    public static final int CRITICAL_IP_BLOCK_MINUTES = 24 * 60;
    /** HIGH 封 IP 时长（分钟）。 */
    public static final int HIGH_IP_BLOCK_MINUTES = 60;

    private final SystemSettingService systemSettingService;
    private final BanService banService;
    private final IpBlacklistService ipBlacklistService;
    private final BizMetrics bizMetrics;

    public AutoResponder(SystemSettingService systemSettingService,
                         BanService banService,
                         IpBlacklistService ipBlacklistService,
                         BizMetrics bizMetrics) {
        this.systemSettingService = systemSettingService;
        this.banService = banService;
        this.ipBlacklistService = ipBlacklistService;
        this.bizMetrics = bizMetrics;
    }

    /** 按矩阵处置一次命中。任何异常吞掉（安全处置故障不阻监控主链）。 */
    public void execute(SecurityRule.Verdict verdict) {
        try {
            if (!getBool(KEY_AUTO_ENABLED, true)) {
                return; // 总闸关：只告警
            }
            String severity = verdict.severity();
            if (SecurityEventTypes.SEV_CRITICAL.equals(severity)) {
                lockAccountIfAllowed(verdict);
                blockIpIfAllowed(verdict, CRITICAL_IP_BLOCK_MINUTES);
            } else if (SecurityEventTypes.SEV_HIGH.equals(severity)) {
                String action = verdict.autoAction();
                if (SecurityEventTypes.ACT_ACCOUNT_LOCKED.equals(action)) {
                    lockAccountIfAllowed(verdict);
                } else if (SecurityEventTypes.ACT_IP_BLOCKED.equals(action)) {
                    blockIpIfAllowed(verdict, HIGH_IP_BLOCK_MINUTES);
                }
                // 其他 autoAction（NONE/null）：只告警
            }
            // MEDIUM/LOW：只告警
        } catch (Exception e) {
            log.error("自动处置异常(已吞) eventType={} userId={} ip={} : {}",
                    verdict.eventType(), verdict.userId(), verdict.clientIp(), e.toString());
        }
    }

    private void lockAccountIfAllowed(SecurityRule.Verdict verdict) {
        if (verdict.userId() == null || !getBool(KEY_AUTO_ACCOUNT_LOCK, true)) {
            return;
        }
        banService.lockAccount(verdict.userId(), LOCK_MINUTES, verdict.eventType());
        bizMetrics.accountLocked("lock");
        log.warn("自动锁号 eventType={} userId={} minutes={}", verdict.eventType(), verdict.userId(), LOCK_MINUTES);
    }

    private void blockIpIfAllowed(SecurityRule.Verdict verdict, int minutes) {
        String ip = verdict.clientIp();
        if (ip == null || ip.isBlank() || !getBool(KEY_AUTO_IP_BLOCK, true)) {
            return;
        }
        ipBlacklistService.autoBlock(ip, verdict.eventType(), minutes);
        bizMetrics.ipBlocked(SecurityEventTypes.SRC_AUTO);
        log.warn("自动封IP eventType={} ip={} minutes={}", verdict.eventType(), ip, minutes);
    }

    private boolean getBool(String key, boolean def) {
        try {
            return systemSettingService.getBoolean(key, def);
        } catch (Exception e) {
            log.warn("自动处置开关读取失败(用默认{}) key={} : {}", def, key, e.getMessage());
            return def;
        }
    }
}
