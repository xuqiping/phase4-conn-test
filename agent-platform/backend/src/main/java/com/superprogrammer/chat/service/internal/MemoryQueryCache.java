package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.entity.UserMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 记忆查询 Redis 缓存（砍每轮 DB 往返）。V33 加项目记忆 scope：key 带 scopeSig，
 * 不同 scope（global/项目组合）各占独立缓存项。
 * <p>范式照抄 {@code VisibilitySetService}：{@code StringRedisTemplate} + prefix + opsForValue + TTL + 异常降级 DB。
 * <p>正确性靠 {@link #evictUser(Long)} 写时失效（TTL 60s 仅崩溃兜底——纯 TTL 会让新插事实对下轮 findCleanByBlock 不可见 → 漏冲突）。
 * 写时失效策略：一条记忆可挂多 scope，难精确算哪些 cache 受影响 → flush 该用户全前缀（略过失效但保证正确）。
 * 缓存项：scope 内 distinct memory_key 列表 + (scope,块) clean 成员。不缓存 sameKey 查询（基数高、dedup 须最新）。
 * <p>UserMemory 实体不映射 embedding 列（见 UserMemoryMapper）→ JSON 序列化安全。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryQueryCache {

    private static final String PREFIX = "mem:";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** scope 内已存在的 distinct memory_key 列表。miss/异常 → 走 loader 查 DB 并回填。 */
    public List<String> getDistinctKeys(MemoryScope scope, Supplier<List<String>> loader) {
        String key = keysKey(scope);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<String>>() {});
            }
        } catch (Exception e) {
            log.warn("MemoryQueryCache getDistinctKeys 读缓存失败降级DB scope={}: {}", scope, e.getMessage());
        }
        List<String> val = loader.get();
        put(key, val);
        return val;
    }

    /** (scope,块) clean 成员。miss/异常 → loader。 */
    public List<UserMemory> getCleanByBlock(MemoryScope scope, String blockLabel, Supplier<List<UserMemory>> loader) {
        String key = blockKey(scope, blockLabel);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<UserMemory>>() {});
            }
        } catch (Exception e) {
            log.warn("MemoryQueryCache getCleanByBlock 读缓存失败降级DB scope={} block={}: {}", scope, blockLabel, e.getMessage());
        }
        List<UserMemory> val = loader.get();
        put(key, val);
        return val;
    }

    /** V38 LLM_KEY 精排结果缓存：选中 key 列表（scope + queryHash 维度），TTL 60s。
     *  evictUser 已 flush {@code mem:*:userId:*} 覆盖本 key（写时失效优先，TTL 崩溃兜底）。
     *  miss/异常 → 走 loader（粗筛+精排）并回填。摊薄同/近 query 的 LLM 精排调用。 */
    public List<String> getRerankKeys(MemoryScope scope, String queryHash, Supplier<List<String>> loader) {
        String key = PREFIX + "rerank:" + scope.userId() + ":" + scopeSig(scope) + ":" + queryHash;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<String>>() {});
            }
        } catch (Exception e) {
            log.warn("MemoryQueryCache getRerankKeys 读缓存失败降级loader scope={} hash={}: {}", scope, queryHash, e.getMessage());
        }
        List<String> val = loader.get();
        put(key, val);
        return val;
    }

    /** 写时失效该用户全部 scope 的全部缓存项（keys + 所有 block）。任何 user_memories/scope 写入后必须调。 */
    public void evictUser(Long userId) {
        try {
            Set<String> keys = redis.keys(PREFIX + "*:" + userId + ":*");
            // 兜底：老式无 scopeSig 的 key（迁移期）也清
            Set<String> legacyKeys = redis.keys(PREFIX + "keys:" + userId);
            Set<String> legacyBlocks = redis.keys(PREFIX + "block:" + userId + ":*");
            if (keys != null) {
                redis.delete(keys);
            }
            if (legacyKeys != null && !legacyKeys.isEmpty()) redis.delete(legacyKeys);
            if (legacyBlocks != null && !legacyBlocks.isEmpty()) redis.delete(legacyBlocks);
        } catch (Exception e) {
            log.warn("MemoryQueryCache evictUser 失败 userId={}（TTL 60s 兜底）: {}", userId, e.getMessage());
        }
    }

    private String keysKey(MemoryScope scope) {
        return PREFIX + "keys:" + scope.userId() + ":" + scopeSig(scope);
    }

    private String blockKey(MemoryScope scope, String blockLabel) {
        return PREFIX + "block:" + scope.userId() + ":" + scopeSig(scope) + ":" + blockLabel;
    }

    /** scope 指纹：includeGlobal + 升序 projectIds。决定 cache 隔离粒度。 */
    public static String scopeSig(MemoryScope scope) {
        String pids = scope.safeProjectIds().stream()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        return (scope.includeGlobal() ? "G" : "g") + "[" + pids + "]";
    }

    private void put(String key, Object val) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(val), TTL);
        } catch (Exception e) {
            log.warn("MemoryQueryCache 回填缓存失败 key={}: {}", key, e.getMessage());
        }
    }
}
