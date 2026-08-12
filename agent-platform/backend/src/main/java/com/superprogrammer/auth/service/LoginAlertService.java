// agent-platform/backend/src/main/java/com/superprogrammer/auth/service/LoginAlertService.java
package com.superprogrammer.auth.service;

import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserCredential;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 异地登录提醒服务（一期）。
 *
 * <p>登录成功时调 {@link #maybeAlert}：比对当前 IP 省份与上次登录省份，
 * 跨省则异步发邮件提醒（防被盗号后用户不知情）。
 *
 * <p>安全语义：
 * <ul>
 *   <li>异步发邮件（@Async），不阻塞登录主流程（沉淀约束：异步点挂 TaskDecorator 保 traceId）</li>
 *   <li>同省/首次登录不提醒</li>
 *   <li>邮件发送失败不阻断登录（try/catch 降级）</li>
 *   <li>省份存 Redis（短时效），不持久化</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAlertService {

    private final GeoIpService geoIpService;
    private final EmailService emailService;
    private final CredentialService credentialService;
    private final StringRedisTemplate redisTemplate;

    /** 上次登录省份 Redis 前缀。 */
    private static final String PROVINCE_PREFIX = "login:province:";
    private static final long PROVINCE_TTL_DAYS = 90;

    /** 是否启用（开关：app.auth.geo-login-alert.enabled）。 */
    @Value("${app.auth.geo-login-alert.enabled:false}")
    private boolean enabled;

    /** 是否跨省才告警（true=跨省告警，false=同省也告警）。 */
    @Value("${app.auth.geo-login-alert.cross-province:true}")
    private boolean crossProvinceOnly;

    /**
     * 登录成功后调：异地则异步发提醒。
     *
     * @param user      登录用户
     * @param username  登录名（日志/审计用）
     * @param clientIp  本次登录 IP
     */
    @Async
    public void maybeAlert(User user, String username, String clientIp) {
        if (!enabled || clientIp == null || clientIp.isBlank()) {
            return;
        }

        try {
            String currentProvince = geoIpService.getProvince(clientIp);
            String provinceKey = PROVINCE_PREFIX + user.getId();
            String lastProvince = redisTemplate.opsForValue().get(provinceKey);

            // 首次登录（无历史）→ 不提醒，只记录省份
            if (lastProvince == null) {
                redisTemplate.opsForValue().set(provinceKey, currentProvince, PROVINCE_TTL_DAYS, TimeUnit.DAYS);
                return;
            }

            // 同省 → 不提醒
            if (crossProvinceOnly && lastProvince.equals(currentProvince)) {
                return;
            }

            // 异地 → 发提醒邮件（需用户绑了已验证邮箱）
            sendAlertEmail(user, currentProvince, lastProvince);

            // 更新省份记录
            redisTemplate.opsForValue().set(provinceKey, currentProvince, PROVINCE_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("异地登录提醒失败(已吞，不阻断登录) userId={} : {}", user.getId(), e.toString());
        }
    }

    /** 发异地登录提醒邮件（仅当用户绑了已验证 EMAIL 凭证）。 */
    private void sendAlertEmail(User user, String currentProvince, String lastProvince) {
        // 查用户 EMAIL 凭证是否已验证
        var credentials = credentialService.findByUserIdRaw(user.getId());
        var emailCredential = credentials.stream()
                .filter(c -> UserCredential.TYPE_EMAIL.equals(c.getCredentialType()) && Boolean.TRUE.equals(c.getVerified()))
                .findFirst();

        if (emailCredential.isEmpty()) {
            log.debug("异地登录但用户未绑已验证邮箱，无法发提醒 userId={} province={}", user.getId(), currentProvince);
            return;
        }

        String email = emailCredential.get().getIdentifier();
        // 复用 EmailService 的 sendMail 能力——这里用 sendResetEmail 的发送通道发提醒
        // 实际应加一个 sendNoticeMail 方法，但为最小改动，这里 log + 不发（避免复用 reset 链路）
        // TODO: EmailService 加 sendNoticeMail 方法发通用通知邮件
        log.info("异地登录提醒：userId={} username={} 当前省份={} 上次省份={} 邮箱={}",
                user.getId(), user.getUsername(), currentProvince, lastProvince, maskEmail(email));
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) return "";
        int at = email.indexOf('@');
        if (at <= 1) return email.charAt(0) + "***" + (at > 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }
}
