package com.superprogrammer.common.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 通用 Redis 限流器（11x 加固 · P1-C2）。
 *
 * <p>从 {@code AuthService.incrWithWindow}（SEC-FR-001 登录防爆破）提取的通用版：
 * 固定窗口 INCR+首次 EXPIRE；滑动窗口 ZSET 按时间戳记最近 N 秒。</p>
 *
 * <p><b>降级红线</b>：一切 Redis 异常 → 放行（返 true）+ WARN，绝不阻断主链路。
 * 可用性 > 限流（攻击者趁 Redis 抖动突破可接受，由登录锁定/注入特征等内存态兜底）。</p>
 */
@Slf4j
@Component
public class RateLimiter {

    private final StringRedisTemplate redisTemplate;

    /**
     * 固定窗口原子脚本（2026-08-19 事故修复）：INCR + 条件 EXPIRE 一条 Lua 原子完成。
     *
     * <p>旧实现「INCR 后仅 n==1 才补 EXPIRE」存在窗口——首 INCR 成功但 EXPIRE 失败（瞬断被
     * 降级吞掉/两请求竞态/进程恰在两步间被杀）时键永久无 TTL，计数只增不清，累计超 max 后
     * 该维度永久 429（实证：rl:global:0:0:0:0:0:0:0:1 计数 1705、TTL=-1，登录也被拒）。</p>
     *
     * <p>自愈：TTL&lt;0（无过期键，含历史毒键）时重挂 EXPIRE——毒键在下一窗口自然冲掉。</p>
     */
    private static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 or redis.call('TTL', KEYS[1]) < 0 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return n
            """, Long.class);

    public RateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 固定窗口：Lua 原子「INCR 计数 + 无 TTL 则挂 EXPIRE=窗口秒数」。窗口边界有 ≤1 窗口误差（可接受）。
     *
     * @return true=放行；false=超限
     */
    public boolean checkFixed(String key, long max, long windowSeconds) {
        try {
            Long n = redisTemplate.execute(FIXED_WINDOW_SCRIPT, List.of(key), String.valueOf(windowSeconds));
            if (n == null) {
                return true; // Redis 异常路径，放行
            }
            return n <= max;
        } catch (Exception e) {
            log.warn("限流固定窗口 Redis 失败(降级放行) key={} : {}", key, e.getMessage());
            return true;
        }
    }

    /**
     * 滑动窗口：ZSET 清过期 → zCard 计数 → 未超限则 add 当前时间戳。
     * 比固定窗口公平（无边界突刺），代价是多两次 Redis 往返。
     *
     * @return true=放行；false=超限
     */
    public boolean checkSliding(String key, long max, long windowSeconds) {
        try {
            long now = System.currentTimeMillis();
            long from = now - windowSeconds * 1000L;
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, from);
            Long n = redisTemplate.opsForZSet().zCard(key);
            if (n != null && n >= max) {
                return false;
            }
            // member 须唯一（毫秒可重复），否则同毫秒请求互相覆盖致计数缩水
            redisTemplate.opsForZSet().add(key, now + "-" + UUID.randomUUID(), now);
            redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            log.warn("限流滑动窗口 Redis 失败(降级放行) key={} : {}", key, e.getMessage());
            return true;
        }
    }
}
