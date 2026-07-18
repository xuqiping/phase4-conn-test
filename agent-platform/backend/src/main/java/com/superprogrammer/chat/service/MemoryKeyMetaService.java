package com.superprogrammer.chat.service;

import com.superprogrammer.chat.entity.MemoryKeyMeta;
import com.superprogrammer.chat.mapper.MemoryKeyMetaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * M2:per-user per-key 时序事实标记服务。
 * <p>
 * 首次某 key 走到 pending 冲突 → LLM 问用户 → 答案落 memory_key_meta({@link #recordFromAsk})。
 * 后续 merge/resolve 按本标走。panel 可手改({@link #override},source=USER_OVERRIDE)。
 * <p>
 * user 隔离:userId 全程由 controller 从 JWT 注入,不取前端值。
 */
@Service
@RequiredArgsConstructor
public class MemoryKeyMetaService {

    /** 标来源:首次 pending 询问用户所答。 */
    public static final String SOURCE_LLM_ASK = "LLM_ASK";
    /** 标来源:用户在 panel 显式修改(优先级最高,直到下次手改)。 */
    public static final String SOURCE_USER_OVERRIDE = "USER_OVERRIDE";

    private final MemoryKeyMetaMapper mapper;

    /** 读该 (user,key) 的 temporal 标;无则 null(前端 null=首次待询问)。 */
    public MemoryKeyMeta findByUserKey(Long userId, String memoryKey) {
        if (userId == null || memoryKey == null || memoryKey.isBlank()) return null;
        return mapper.findByUserKey(userId, memoryKey);
    }

    /** 便利:是否时序。无标默认 false(非时序,merge 走中文逗号 join 现状,不崩)。 */
    public boolean isTemporal(Long userId, String memoryKey) {
        MemoryKeyMeta m = findByUserKey(userId, memoryKey);
        return m != null && Boolean.TRUE.equals(m.getIsTemporal());
    }

    /**
     * 首次询问落标(LLM_ASK)。已存在则不覆盖(USER_OVERRIDE / 已答过的 LLM_ASK 优先)。
     * @return 落库后的标
     */
    public MemoryKeyMeta recordFromAsk(Long userId, String memoryKey, boolean isTemporal) {
        MemoryKeyMeta existing = findByUserKey(userId, memoryKey);
        if (existing != null) return existing;  // 已有标(用户答过或手改过)→ 不覆盖
        MemoryKeyMeta m = new MemoryKeyMeta();
        m.setUserId(userId);
        m.setMemoryKey(memoryKey);
        m.setIsTemporal(isTemporal);
        m.setSource(SOURCE_LLM_ASK);
        m.setCreatedAt(OffsetDateTime.now());
        m.setUpdatedAt(OffsetDateTime.now());
        mapper.insert(m);
        return m;
    }

    /**
     * panel 手改标(USER_OVERRIDE)。无则新建,有则覆盖(含刷 source 为 USER_OVERRIDE)。
     */
    public MemoryKeyMeta override(Long userId, String memoryKey, boolean isTemporal) {
        MemoryKeyMeta existing = findByUserKey(userId, memoryKey);
        if (existing == null) {
            MemoryKeyMeta m = new MemoryKeyMeta();
            m.setUserId(userId);
            m.setMemoryKey(memoryKey);
            m.setIsTemporal(isTemporal);
            m.setSource(SOURCE_USER_OVERRIDE);
            m.setCreatedAt(OffsetDateTime.now());
            m.setUpdatedAt(OffsetDateTime.now());
            mapper.insert(m);
            return m;
        }
        existing.setIsTemporal(isTemporal);
        existing.setSource(SOURCE_USER_OVERRIDE);
        existing.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(existing);
        return existing;
    }
}
