// agent-platform/backend/src/main/java/com/superprogrammer/common/security/rule/RuleRedisSupport.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.system.service.SystemSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 冷规则公共支撑（11x 加固 · P3-C9）：Redis 窗口计数 + settings 阈值读取 + detailJson 拼接。
 *
 * <p>降级约定（与安全域全局一致）：Redis 故障 → 计数返 -1（规则判不命中，宁可漏报不可误封）；
 * settings 故障 → 用规则默认阈值。任何异常不向上抛（Worker 循环隔离）。</p>
 */
@Slf4j
public abstract class RuleRedisSupport {

    protected final StringRedisTemplate redisTemplate;
    protected final SystemSettingService systemSettingService;

    protected RuleRedisSupport(StringRedisTemplate redisTemplate, SystemSettingService systemSettingService) {
        this.redisTemplate = redisTemplate;
        this.systemSettingService = systemSettingService;
    }

    /**
     * 窗口计数器：INCRBY key delta，首次设 TTL。返累计值；Redis 故障返 -1（不命中）。
     * 键约定：{@code sec:rule:{维度}:{id}}，TTL=窗口秒。
     */
    protected long incrWindow(String key, long delta, long windowSeconds) {
        try {
            Long v = redisTemplate.opsForValue().increment(key, delta);
            if (v != null && v == delta) {
                redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
            }
            return v == null ? -1 : v;
        } catch (Exception e) {
            log.warn("规则计数失败(降级不命中) key={} : {}", key, e.getMessage());
            return -1;
        }
    }

    /** 集合去重计数：SADD member（TTL 首设），返 size；故障返 -1。 */
    protected long saddSize(String key, String member, long windowSeconds) {
        try {
            Long added = redisTemplate.opsForSet().add(key, member);
            if (added != null && added > 0) {
                redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
            }
            Long size = redisTemplate.opsForSet().size(key);
            return size == null ? -1 : size;
        } catch (Exception e) {
            log.warn("规则集合计数失败(降级不命中) key={} : {}", key, e.getMessage());
            return -1;
        }
    }

    /** 读字符串；故障返 null。 */
    protected String get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("规则读取失败(降级) key={} : {}", key, e.getMessage());
            return null;
        }
    }

    /** 写字符串+TTL；故障吞。 */
    protected void set(String key, String value, long windowSeconds) {
        try {
            redisTemplate.opsForValue().set(key, value, windowSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("规则写入失败(已吞) key={} : {}", key, e.getMessage());
        }
    }

    /** settings 阈值读取（故障用默认）。 */
    protected long threshold(String key, long def) {
        try {
            return systemSettingService.getLong(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    /** detailJson 字段转义（防引号/反斜杠/控制字符破坏 JSON）。 */
    protected static String esc(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replaceAll("[\\p{Cntrl}]", " ");
        return t.length() > 120 ? t.substring(0, 120) : t;
    }
}
