// agent-platform/backend/src/main/java/com/superprogrammer/common/security/IpBlacklistService.java
package com.superprogrammer.common.security;

import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.common.security.entity.IpBlacklist;
import com.superprogrammer.common.security.mapper.IpBlacklistMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

/**
 * IP 黑名单服务（11x 加固 · P2-C6）：DB 持久 + Redis 镜像，热路径查 Redis 不查库。
 *
 * <p>一致性：所有增删走本服务双写（upsert/delete + Redis set/del），不直接改表；
 * 启动 {@link #loadIntoRedis()} 兜底重灌（Redis 重启/漂移自愈）。</p>
 *
 * <p>过期语义：banned_until 到期 → Redis key TTL 自然消失（自动解封）；
 * DB 过期行每小时定时物理删（取证残渣清理）。永久封（banned_until=null）只手动解。</p>
 *
 * <p>降级：Redis 故障 isBlocked → false 放行（可用性 > 强制力；注入特征/限流仍在拦）。</p>
 */
@Slf4j
@Service
public class IpBlacklistService {

    /** Redis 镜像键前缀：ipban:{ip}。 */
    public static final String REDIS_PREFIX = "ipban:";
    /** 永久封 Redis 兜底 TTL：30 天（无 TTL 的 key 防 Redis 重启丢持久化外的永久丢失；启动加载会重灌）。 */
    private static final long PERMANENT_REDIS_TTL_DAYS = 30;

    private final IpBlacklistMapper ipBlacklistMapper;
    private final StringRedisTemplate redisTemplate;
    private final BizMetrics bizMetrics;

    public IpBlacklistService(IpBlacklistMapper ipBlacklistMapper,
                              StringRedisTemplate redisTemplate,
                              BizMetrics bizMetrics) {
        this.ipBlacklistMapper = ipBlacklistMapper;
        this.redisTemplate = redisTemplate;
        this.bizMetrics = bizMetrics;
    }

    /** 启动加载：未过期行灌 Redis（Redis 重启/双写漂移兜底）。加载失败仅 WARN（启动不阻塞）。 */
    @PostConstruct
    public void loadIntoRedis() {
        try {
            var active = ipBlacklistMapper.selectActive(OffsetDateTime.now());
            int loaded = 0;
            for (IpBlacklist row : active) {
                mirrorToRedis(row.getIp(), row.getBannedUntil());
                loaded++;
            }
            if (loaded > 0) {
                log.warn("IP 黑名单启动加载完成 count={}", loaded);
            }
        } catch (Exception e) {
            log.warn("IP 黑名单启动加载失败(降级,运行期双写仍生效) : {}", e.getMessage());
        }
    }

    /** 热路径查询：Redis hasKey。故障 → false 放行。 */
    public boolean isBlocked(String ip) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(REDIS_PREFIX + ip));
        } catch (Exception e) {
            log.warn("IP 黑名单查询失败(降级放行) ip={} : {}", ip, e.getMessage());
            return false;
        }
    }

    /**
     * 自动封 IP（热规则触发）。重复触发同 IP = 续期（upsert）。
     *
     * @param durationMinutes 封禁时长（自动封偏短防 NAT 误伤，规则默认 30~60min）
     */
    public void autoBlock(String ip, String reason, long durationMinutes) {
        String normalized = normalize(ip);
        OffsetDateTime until = OffsetDateTime.now().plusMinutes(durationMinutes);
        try {
            ipBlacklistMapper.upsert(normalized, SecurityEventTypes.SRC_AUTO, reason, until, reason);
            mirrorToRedis(normalized, until);
            bizMetrics.ipBlocked(SecurityEventTypes.SRC_AUTO);
            log.warn("IP 自动封禁 ip={} reason={} minutes={}", normalized, reason, durationMinutes);
        } catch (Exception e) {
            log.error("IP 自动封禁失败(已吞) ip={} reason={} : {}", normalized, reason, e.getMessage());
        }
    }

    /** 手动封 IP（admin）。permanent=true → banned_until=null（永久，只手动解）。 */
    public void manualBlock(String ip, String reason, boolean permanent, String operator) {
        String normalized = normalize(ip);
        OffsetDateTime until = permanent ? null : OffsetDateTime.now().plusHours(24);
        try {
            ipBlacklistMapper.upsert(normalized, SecurityEventTypes.SRC_MANUAL, reason, until, operator);
            mirrorToRedis(normalized, until);
            bizMetrics.ipBlocked(SecurityEventTypes.SRC_MANUAL);
            log.warn("IP 手动封禁 ip={} permanent={} operator={} reason={}", normalized, permanent, operator, reason);
        } catch (Exception e) {
            log.error("IP 手动封禁失败 ip={} : {}", normalized, e.getMessage());
            throw new IllegalStateException("封禁失败：" + e.getMessage());
        }
    }

    /** 解封：DB + Redis 双删。 */
    public void unblock(String ip, String operator) {
        String normalized = normalize(ip);
        try {
            ipBlacklistMapper.deleteByIp(normalized);
            redisTemplate.delete(REDIS_PREFIX + normalized);
            log.warn("IP 解封 ip={} operator={}", normalized, operator);
        } catch (Exception e) {
            log.error("IP 解封失败 ip={} : {}", normalized, e.getMessage());
            throw new IllegalStateException("解封失败：" + e.getMessage());
        }
    }

    /** 每小时清 DB 过期行（Redis key 靠 TTL 自愈）。 */
    @Scheduled(fixedDelay = 3600_000L, initialDelay = 600_000L)
    public void purgeExpired() {
        try {
            int deleted = ipBlacklistMapper.deleteExpired(OffsetDateTime.now());
            if (deleted > 0) {
                log.info("IP 黑名单过期行清理 count={}", deleted);
            }
        } catch (Exception e) {
            log.warn("IP 黑名单过期清理失败(已吞,下小时重试) : {}", e.getMessage());
        }
    }

    /** IP 归一化：InetAddress 标准形态（IPv6 压缩统一，防多种写法绕过封禁）。非法输入原样返回。 */
    public static String normalize(String ip) {
        if (ip == null || ip.isBlank()) {
            return ip;
        }
        try {
            return InetAddress.getByName(ip.trim()).getHostAddress();
        } catch (Exception e) {
            return ip.trim();
        }
    }

    /** DB 行 → Redis 镜像（TTL=剩余封禁期；永久给 30 天兜底，启动加载会重灌）。 */
    private void mirrorToRedis(String ip, OffsetDateTime bannedUntil) {
        try {
            long ttlSeconds = bannedUntil == null
                    ? TimeUnit.DAYS.toSeconds(PERMANENT_REDIS_TTL_DAYS)
                    : Math.max(60, Duration.between(OffsetDateTime.now(), bannedUntil).getSeconds());
            redisTemplate.opsForValue().set(REDIS_PREFIX + ip, "1", ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("IP 黑名单 Redis 镜像失败(降级,DB 已持久) ip={} : {}", ip, e.getMessage());
        }
    }
}
