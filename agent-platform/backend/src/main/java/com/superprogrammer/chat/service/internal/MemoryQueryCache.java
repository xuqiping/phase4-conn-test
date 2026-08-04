package com.superprogrammer.chat.service.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 记忆写时失效（计划12 · H 收尾瘦身版）。
 * <p>
 * <b>v2 瘦身（H'-2）</b>：legacy {@code user_memories} 的读缓存（getDistinctKeys / getCleanByBlock /
 * getRerankKeys + scopeSig 指纹）已随旧栈整体删除——新栈召回走 {@code MemoryRecallPipeline}（halfvec + BM25），
 * 不再走 key/block Redis 缓存。本类现仅保留 {@link #evictUser(Long)}：新栈写入/总结/冲突解决后调，
 * flush 该用户残留 legacy 缓存项（迁移期 TTL 60s 兜底），并防未来召回层再加缓存时复用同一失效入口。
 * <p>范式照抄 {@code VisibilitySetService}：{@code StringRedisTemplate} + prefix + 异常降级仅日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryQueryCache {

    private static final String PREFIX = "mem:";

    private final StringRedisTemplate redis;

    /** 失效该用户全部 legacy 缓存项（keys + 所有 block + rerank）。
     *  新栈 {@code MemoryGenerationService}/{@code MemoryConsolidationService}/{@code MemoryConflictResolutionService}
     *  写入后调用（向量 9，不等 TTL）。 */
    public void evictUser(Long userId) {
        try {
            Set<String> keys = redis.keys(PREFIX + "*:" + userId + ":*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            log.warn("MemoryQueryCache evictUser 失败 userId={}: {}", userId, e.getMessage());
        }
    }
}
