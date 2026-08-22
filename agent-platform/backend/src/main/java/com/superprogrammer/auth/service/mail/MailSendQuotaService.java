package com.superprogrammer.auth.service.mail;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 邮件发送配额（12x B3 反轰炸）：
 * <ul>
 *   <li>IP 维度：同 IP 每小时最多发 10 封（注册发码/重发验证/找回密码共用池）——
 *       堵「拿平台当免费轰炸机给别人的邮箱刷屏」；</li>
 *   <li>全局日总量：默认 500 封/天（system_settings 键 auth.channel.mail.daily-cap 可调）——
 *       腾讯等个人邮箱本身限日量，超限会被服务商封号，平台先自我封顶；</li>
 *   <li>Redis 故障降级放行（与注册/登录限流同范式，可用性优先）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailSendQuotaService {

    /** 日封顶配置键（system_settings）。 */
    public static final String KEY_DAILY_CAP = "auth.channel.mail.daily-cap";
    private static final int DEFAULT_DAILY_CAP = 500;
    private static final String IP_PREFIX = "mailsend:ip:";
    private static final long IP_WINDOW_SECONDS = 3600;
    private static final int IP_HOURLY_MAX = 10;
    private static final String DAILY_PREFIX = "mailsend:daily:";

    private final StringRedisTemplate redisTemplate;
    private final SystemSettingService systemSettingService;

    /** IP 小时窗口检查（超额抛 RATE_LIMIT）。 */
    public void checkIpHourly(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }
        try {
            Long n = redisTemplate.opsForValue().increment(IP_PREFIX + clientIp);
            if (n != null && n == 1L) {
                redisTemplate.expire(IP_PREFIX + clientIp, IP_WINDOW_SECONDS, TimeUnit.SECONDS);
            }
            if (n != null && n > IP_HOURLY_MAX) {
                log.warn("邮件发送 IP 限流触发 ip={} count={}", clientIp, n);
                throw new BusinessException(ErrorCode.RATE_LIMIT, "发送过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("邮件 IP 限流 Redis 失败(降级放行) : {}", e.toString());
        }
    }

    /**
     * 全局日总量消费：未超→计数+1 返 true；已超→记 ERROR 返 false（调用方按发信失败处理）。
     */
    public boolean tryConsumeDaily() {
        int cap = resolveDailyCap();
        String key = DAILY_PREFIX + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        try {
            Long n = redisTemplate.opsForValue().increment(key);
            if (n != null && n == 1L) {
                redisTemplate.expire(key, 36, TimeUnit.HOURS);
            }
            if (n != null && n > cap) {
                log.error("邮件日发送总量封顶触发 cap={} count={}——防服务商封号，今日后续发信全部拒绝", cap, n);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("邮件日配额 Redis 失败(降级放行) : {}", e.toString());
            return true;
        }
    }

    private int resolveDailyCap() {
        try {
            return (int) systemSettingService.getLong(KEY_DAILY_CAP, DEFAULT_DAILY_CAP);
        } catch (Exception e) {
            log.warn("邮件日封顶配置读取失败(用默认 {}) : {}", DEFAULT_DAILY_CAP, e.toString());
            return DEFAULT_DAILY_CAP;
        }
    }
}
