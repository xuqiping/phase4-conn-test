// agent-platform/backend/src/main/java/com/superprogrammer/common/security/LoginAttemptsService.java
package com.superprogrammer.common.security;

import com.superprogrammer.common.security.entity.LoginAttempt;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.common.security.mapper.LoginAttemptMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 登录尝试取证服务（11x 加固 · P2-C7）：异步写 login_attempts，绝不阻登录主链。
 *
 * <p>异步池：core1/max2，队列 500，CallerRuns 背压（队列满→调用线程写，自然限速）；
 * 单条失败吞（取证丢失可接受）。30 天滚动清理（每日 04:30）。</p>
 *
 * <p>检测计数不走本表（走 Redis login:fail:* / login:ids:*），本表只留取证与异地检测数据源。</p>
 */
@Slf4j
@Service
public class LoginAttemptsService {

    /** 留存期：30 天（V104 表注释口径）。 */
    private static final long RETENTION_DAYS = 30;

    private final LoginAttemptMapper loginAttemptMapper;
    private final GeoIpService geoIpService;
    private final ApplicationEventPublisher eventPublisher;

    /** 专用小池：core1/max2/queue500，CallerRuns 背压。命名 login-attempt-* 便于 jstack 定位。 */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 2, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(500),
            runnable -> {
                Thread t = new Thread(runnable, "login-attempt-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    public LoginAttemptsService(LoginAttemptMapper loginAttemptMapper, GeoIpService geoIpService,
                                ApplicationEventPublisher eventPublisher) {
        this.loginAttemptMapper = loginAttemptMapper;
        this.geoIpService = geoIpService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 异步记一次登录尝试。geo 在异步线程里查（ip2region 内存查询微秒级，但主链零依赖）。
     */
    public void recordAsync(String identifier, Long userId, String clientIp,
                            boolean success, String failReason) {
        try {
            executor.execute(() -> {
                try {
                    LoginAttempt row = new LoginAttempt();
                    row.setIdentifier(identifier);
                    row.setUserId(userId);
                    row.setClientIp(clientIp == null ? "unknown" : clientIp);
                    row.setSuccess(success);
                    row.setFailReason(failReason);
                    row.setGeo(geoIpService.lookup(clientIp));
                    loginAttemptMapper.insert(row);
                    // P3-C9 接线：登录成功 → 发 KIND_LOGIN_SUCCESS（异地/Token盗号规则消费，异步已在池内）
                    if (success && userId != null) {
                        eventPublisher.publishEvent(ApplicationSecurityEvent.of(this,
                                ApplicationSecurityEvent.KIND_LOGIN_SUCCESS, userId, clientIp,
                                Map.of("geo", row.getGeo() == null ? "" : row.getGeo())));
                    }
                } catch (Exception e) {
                    log.warn("登录尝试落库失败(已吞) identifier={} : {}", identifier, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("登录尝试提交线程池失败(已吞) identifier={} : {}", identifier, e.getMessage());
        }
    }

    /** 30 天滚动清理（每日 04:30，避开业务高峰）。 */
    @Scheduled(cron = "0 30 4 * * ?")
    public void purgeExpired() {
        try {
            int deleted = loginAttemptMapper.deleteOlderThan(OffsetDateTime.now().minusDays(RETENTION_DAYS));
            if (deleted > 0) {
                log.info("login_attempts 30 天滚动清理 count={}", deleted);
            }
        } catch (Exception e) {
            log.warn("login_attempts 清理失败(已吞,明日重试) : {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
