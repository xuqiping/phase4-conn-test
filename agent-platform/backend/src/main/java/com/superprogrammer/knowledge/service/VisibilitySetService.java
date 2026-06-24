package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.auth.entity.UserDepartment;
import com.superprogrammer.auth.entity.UserRole;
import com.superprogrammer.auth.mapper.UserDepartmentMapper;
import com.superprogrammer.auth.mapper.UserRoleMapper;
import com.superprogrammer.knowledge.config.VisibilityCacheProperties;
import com.superprogrammer.knowledge.mapper.VisibilityQueryMapper;
import com.superprogrammer.knowledge.service.internal.VisibleDocSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 权限可见集缓存（v6 §5.2，阶段4-A）。
 * cache-first：admin→全量（不缓存）；命中→返回；miss→per-key mutex + double-check→DB 三层并集→writeback。
 * Redis 故障不阻断检索（降级 DB 直算）。per-key 互斥锁单实例（多实例留 SETNX）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisibilitySetService {

    private static final Long TENANT_ID = 1L;
    private static final String KEY_PREFIX = "vis:";
    private static final String IDENTITY = "USER";
    private static final String SENTINEL_ALL = "{\"all\":true}";

    private final VisibilityQueryMapper visibilityQueryMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserDepartmentMapper userDepartmentMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final VisibilityCacheProperties props;

    /** per-key 锁条带（单实例）。 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public VisibleDocSet getVisibleDocs(Long kbId, Long userId, boolean admin) {
        if (admin || userId == null) {
            return VisibleDocSet.all();   // admin/owner：全量，不缓存
        }
        if (!props.isEnabled()) {
            return computeFromDb(kbId, userId);
        }
        String key = buildKey(userId, kbId);

        // 1. cache read（Redis 故障降级直算）
        VisibleDocSet cached = readCache(key);
        if (cached != null) {
            return cached;
        }
        // 2. per-key mutex + double-check
        Object lockObj = locks.computeIfAbsent(key, k -> new Object());
        try {
            synchronized (lockObj) {
                cached = readCache(key);
                if (cached != null) {
                    return cached;
                }
                VisibleDocSet result = computeFromDb(kbId, userId);
                writeCache(key, result);
                return result;
            }
        } finally {
            locks.remove(key);   // 防 map 无界；remove 后新线程建新锁，已在块内的线程仍持旧锁，无竞态
        }
    }

    // ============================ compute ============================

    private VisibleDocSet computeFromDb(Long kbId, Long userId) {
        List<Long> roleIds = roleIdsOf(userId);
        List<Long> deptIds = deptIdsOf(userId);
        if (visibilityQueryMapper.hasKbLevelRead(TENANT_ID, kbId, userId, roleIds, deptIds)) {
            return VisibleDocSet.all();
        }
        List<Long> docs = visibilityQueryMapper.computeVisibleDocs3Layer(TENANT_ID, kbId, userId, roleIds, deptIds);
        return VisibleDocSet.of(new LinkedHashSet<>(docs));
    }

    private List<Long> roleIdsOf(Long userId) {
        LambdaQueryWrapper<UserRole> w = new LambdaQueryWrapper<>();
        w.eq(UserRole::getUserId, userId).select(UserRole::getRoleId);
        return userRoleMapper.selectList(w).stream()
                .map(UserRole::getRoleId).distinct().toList();
    }

    private List<Long> deptIdsOf(Long userId) {
        LambdaQueryWrapper<UserDepartment> w = new LambdaQueryWrapper<>();
        w.eq(UserDepartment::getUserId, userId).select(UserDepartment::getDepartmentId);
        return userDepartmentMapper.selectList(w).stream()
                .map(UserDepartment::getDepartmentId).distinct().toList();
    }

    // ============================ cache IO ============================

    private VisibleDocSet readCache(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            return json == null ? null : deserialize(json);
        } catch (RuntimeException e) {
            log.warn("可见集缓存读失败 key={}: {}（降级 DB）", key, e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, VisibleDocSet set) {
        try {
            redisTemplate.opsForValue().set(key, serialize(set), props.getTtlMs(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.warn("可见集缓存写失败 key={}: {}", key, e.getMessage());
        }
    }

    private String serialize(VisibleDocSet set) {
        try {
            return set.isAll() ? SENTINEL_ALL
                    : objectMapper.writeValueAsString(java.util.Map.of("all", false, "docs", set.docsOrEmpty()));
        } catch (Exception e) {
            throw new RuntimeException("可见集序列化失败: " + e.getMessage(), e);
        }
    }

    private VisibleDocSet deserialize(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            boolean all = node.path("all").asBoolean(false);
            if (all) {
                return VisibleDocSet.all();
            }
            List<Long> docs = objectMapper.convertValue(node.path("docs"), new TypeReference<List<Long>>() {});
            return VisibleDocSet.of(new LinkedHashSet<>(docs));
        } catch (Exception e) {
            log.warn("可见集反序列化失败，按 miss 处理: {}", e.getMessage());
            return null;
        }
    }

    private String buildKey(Long userId, Long kbId) {
        return KEY_PREFIX + TENANT_ID + ":" + IDENTITY + ":" + userId + ":" + kbId;
    }

    /** 仅供 listener/调试枚举某 KB 的缓存 key pattern。 */
    public static String kbKeyPattern(Long kbId) {
        return KEY_PREFIX + "*:*:" + kbId;
    }
}
