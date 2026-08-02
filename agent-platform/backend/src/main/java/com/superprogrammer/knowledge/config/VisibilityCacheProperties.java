package com.superprogrammer.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 可见集缓存参数（v6 §5.2，阶段4-A）。
 * application.yml: rag.visibility-cache.{enabled,ttl-ms,scan-count}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.visibility-cache")
public class VisibilityCacheProperties {

    /** 总开关（关 → 走 DB 直算，不读写 Redis）。 */
    private boolean enabled = true;

    /** 缓存 TTL（毫秒），默认 30min。 */
    private long ttlMs = 1_800_000L;

    /** Redis SCAN 每轮 count 提示。 */
    private int scanCount = 100;
}
