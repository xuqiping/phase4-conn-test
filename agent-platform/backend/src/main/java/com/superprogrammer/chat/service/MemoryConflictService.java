package com.superprogrammer.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.dto.MemoryCandidateVO;
import com.superprogrammer.chat.dto.MemoryConflictVO;
import com.superprogrammer.chat.entity.MemoryConflict;
import com.superprogrammer.chat.entity.UserMemory;
import com.superprogrammer.chat.mapper.MemoryConflictMapper;
import com.superprogrammer.chat.mapper.UserMemoryMapper;
import com.superprogrammer.chat.service.internal.MemoryValueTimeline;
import com.superprogrammer.knowledge.service.RagConfig;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import com.superprogrammer.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆冲突解决服务（V27）。
 * 生命周期：createPending（锁会话）→ getActivePendingOrExpire（懒过期 flag）→ resolve（用户决定）/ flag（无关·超时·共存）。
 * FLAGGED：新事实入库 + 新旧 conflict_id 都指本 conflict → 分组可见。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryConflictService {

    private final MemoryConflictMapper conflictMapper;
    private final UserMemoryMapper memoryMapper;
    private final ObjectMapper objectMapper;
    private final com.superprogrammer.chat.service.internal.MemoryQueryCache queryCache;
    /** KEEP_BOTH 合并后对 merged value 重 embed（失败兜底保留旧向量）。 */
    private final LlmGateway llmGateway;
    /** M2:per-key 时序标——KEEP_BOTH merge 据此分时间线(带日期段)/非时序(中文逗号 join)两分支。 */
    private final MemoryKeyMetaService keyMetaService;

    /** 建 PENDING（锁会话）。newFact 暂存快照，不入 user_memories。 */
    @Transactional
    public MemoryConflict createPending(Long userId, Long sessionId, String blockLabel,
                                        ExtractedFactSnapshot snap, List<Long> existingIds, String askText) {
        MemoryConflict c = new MemoryConflict();
        c.setUserId(userId);
        c.setSessionId(sessionId);
        c.setBlockLabel(blockLabel);
        c.setAskText(askText);
        c.setStatus("PENDING");
        c.setExpiresAt(OffsetDateTime.now().plusMinutes(RagConfig.MEMORY_CONFLICT_EXPIRE_MIN));
        try {
            c.setNewMemory(objectMapper.writeValueAsString(snapToMap(snap)));
            c.setNewEmbedding(snap.halfvec());
            c.setExistingMemoryIds(existingIds);
            // PG bigint[] 字面量须 {id1,id2}（花括无空格），非 List.toString() 的 [a, b]
            String arrLiteral = "{" + String.join(",", existingIds.stream().map(String::valueOf).toList()) + "}";
            conflictMapper.insertConflict(c, arrLiteral);
        } catch (Exception e) {
            log.warn("createPending 失败: {}", e.getMessage());
        }
        return c;
    }

    /** 锁忙时直建 FLAGGED（绕 PENDING 单锁，不丢事实）。
     *  new 立即带 conflict_id 入库 + old 同组打标 → 面板可见（findActiveByUser 含 FLAGGED）/ 可批 resolve / 不占 PENDING 锁不阻塞后续。
     *  FLAGGED 的 clean 唯一约束绕过：new 与 old 均带 conflict_id，不进 clean 偏唯一索引。 */
    @Transactional
    public MemoryConflict createFlagged(Long userId, Long sessionId, String blockLabel,
                                        ExtractedFactSnapshot snap, List<Long> existingIds,
                                        Long writeTargetProjectId) {
        MemoryConflict c = new MemoryConflict();
        c.setUserId(userId);
        c.setSessionId(sessionId);
        c.setBlockLabel(blockLabel);
        c.setAskText(null);              // 非首轮：不打断回复，仅面板待办
        c.setStatus("FLAGGED");
        c.setExpiresAt(null);
        try {
            c.setNewMemory(objectMapper.writeValueAsString(snapToMap(snap)));
            c.setNewEmbedding(snap.halfvec());
            c.setExistingMemoryIds(existingIds);
            String arrLiteral = "{" + String.join(",", existingIds.stream().map(String::valueOf).toList()) + "}";
            conflictMapper.insertConflict(c, arrLiteral);
            // new 带 conflict_id 入库（与 old 共存，绕 clean 唯一约束）
            // V33：is_global=(写目标==null)，project 写目标→挂 user_memory_projects（冲突行也归正确 scope）
            UserMemory m = new UserMemory();
            m.setUserId(userId);
            m.setCategory(snap.category());
            m.setMemoryKey(snap.key());
            m.setMemoryKeyZh(snap.keyZh());
            m.setMemoryValue(snap.value());
            m.setSource("INFERRED");
            m.setConfidence(new BigDecimal(snap.confidence()));
            m.setBlockLabel(blockLabel);
            m.setConflictId(c.getId());
            m.setIsGlobal(writeTargetProjectId == null);
            // V34：冲突行也记 home（与写目标对齐）；resolve 后 survivor 清 conflict_id 变 clean 时 home 已正确，不撞唯一约束
            m.setHomeProjectId(writeTargetProjectId);
            m.setEntities(snap.entitiesJson());
            // V38 bug 修：冲突新行同 applyClean 落 key_zh/entities + anchor 两列（否则 KEEP_NEW survivor 召回词袋/中文标签丢）
            memoryMapper.insertMemory(m, snap.halfvec(), snap.anchorHalfvec(), snap.anchorTokens());
            if (writeTargetProjectId != null) {
                memoryMapper.insertMemoryProjects(m.getId(), List.of(writeTargetProjectId));
            }
            // old + new 同组打标
            List<Long> all = new ArrayList<>(existingIds);
            all.add(m.getId());
            memoryMapper.setConflictId(all, c.getId());
            queryCache.evictUser(userId);
        } catch (Exception e) {
            log.warn("createFlagged 失败: {}", e.getMessage());
        }
        return c;
    }

    /** 查活跃 PENDING；若已过期→懒触发 flag 并返回 null。 */
    @Transactional
    public MemoryConflict getActivePendingOrExpire(Long sessionId, Long userId) {
        MemoryConflict c = conflictMapper.findActivePending(sessionId, userId);
        if (c == null) return null;
        if (c.getExpiresAt() != null && c.getExpiresAt().isBefore(OffsetDateTime.now())) {
            flag(c);   // 超时→共存打标
            return null;
        }
        return c;
    }

    /** FLAGGED：新事实入库 + 新旧都指本 conflict。 */
    @Transactional
    public void flag(MemoryConflict c) {
        try {
            Map<String, Object> snap = readSnap(c.getId());
            String halfvec = conflictMapper.getNewEmbeddingText(c.getId());
            List<Long> ids = parseCsv(conflictMapper.getExistingIdsCsv(c.getId()));
            // V35：从 old 行继承 scope（home + 可见性 + 项目），否则新行落默认 global 撞既有 global 同 key 唯一约束
            List<UserMemory> oldRows = ids.isEmpty() ? List.of() : memoryMapper.selectBatchIds(ids);
            UserMemory ref = oldRows.isEmpty() ? null : oldRows.get(0);
            Long home = ref == null ? null : ref.getHomeProjectId();
            Boolean isGlobal = ref == null ? null : ref.getIsGlobal();
            List<Long> pids = ref == null ? List.of() : memoryMapper.findProjectIdsByMemory(ref.getId());
            UserMemory m = buildFromSnap(c.getUserId(), c.getBlockLabel(), snap, home, isGlobal);
            memoryMapper.insertMemory(m, halfvec, (String) snap.get("anchorHalfvec"), (String) snap.get("anchorTokens"));
            if (!Boolean.TRUE.equals(m.getIsGlobal()) && !pids.isEmpty()) {
                memoryMapper.insertMemoryProjects(m.getId(), pids);
            }
            ids.add(m.getId());
            memoryMapper.setConflictId(ids, c.getId());
            conflictMapper.updateStatus(c.getId(), "FLAGGED", "FLAGGED");
            queryCache.evictUser(c.getUserId());
        } catch (Exception e) {
            log.warn("flag 失败 conflictId={}: {}", c.getId(), e.getMessage());
        }
    }

    /** 批量解决当前用户全部待处理冲突（PENDING+FLAGGED），统一 decision。返回成功条数。 */
    public int resolveAll(Long userId, String decision) {
        // 批量不含 KEEP_CUSTOM(需逐条 customValue,无统一值);customValue 传 null
        if (!List.of("KEEP_NEW", "KEEP_OLD", "KEEP_BOTH", "DISCARD").contains(decision)) return 0;
        List<MemoryConflict> list = conflictMapper.findActiveByUser(userId);
        int n = 0;
        for (MemoryConflict c : list) {
            if (resolve(userId, c.getId(), decision, null)) n++;
        }
        return n;
    }

    /** 用户待处理冲突计数（PENDING+FLAGGED）—— 状态条 3s 轮询用，省去拉全量 list。 */
    public int countActive(Long userId) {
        return conflictMapper.countActiveByUser(userId);
    }

    /**
     * 历史 KEEP_BOTH 脏数据清理：把 conflict 已 RESOLVED 但行仍带 conflict_id 的残留（旧"双行共存"
     * 语义遗留——resolve 后未清 conflict_id → 永久带标 + 抽取去重隐身），按 (user_id, memory_key)
     * 分组合并成一条 clean（单行则仅去 conflict_id）。返处理行数。幂等：再跑无残留。
     * 维护端点经 {@link MemoryService#cleanupResolvedResidueAsync()} 异步触发（避免 HTTP 超时）。
     */
    public int cleanupResolvedResidue() {
        List<UserMemory> rows = memoryMapper.findResolvedFlaggedRows();
        if (rows.isEmpty()) {
            log.info("memoryCleanup 无 RESOLVED 残留行（全部已 clean）");
            return 0;
        }
        log.info("memoryCleanup 待清理 {} 行", rows.size());
        // 按 (userId, memoryKey) 分组，id 升序（survivor=最老一行）
        Map<String, List<UserMemory>> groups = new LinkedHashMap<>();
        for (UserMemory m : rows) {
            String k = m.getUserId() + "" + m.getMemoryKey();
            groups.computeIfAbsent(k, x -> new ArrayList<>()).add(m);
        }
        int touched = 0;
        Set<Long> touchedUsers = new HashSet<>();
        for (List<UserMemory> g : groups.values()) {
            UserMemory survivor = g.get(0);
            List<Long> restIds = g.stream().map(UserMemory::getId)
                    .filter(id -> !id.equals(survivor.getId())).toList();
            hardDelete(restIds);
            if (restIds.isEmpty()) {
                // 单行残留：仅清 conflict_id（值不变，COALESCE 保旧向量）
                memoryMapper.mergeIntoRow(survivor.getId(), survivor.getMemoryValue(), null, null, null);
            } else {
                List<String> values = new ArrayList<>();
                g.forEach(m -> values.add(m.getMemoryValue()));
                mergeValuesInto(survivor.getUserId(), survivor.getMemoryKey(), survivor.getId(), values, OffsetDateTime.now());
            }
            touched += g.size();
            touchedUsers.add(survivor.getUserId());
        }
        touchedUsers.forEach(queryCache::evictUser);
        log.info("memoryCleanup 完成，处理 {} 行 / {} 组", touched, groups.size());
        return touched;
    }

    /** 执行用户决定。PENDING（new 未入库）与 FLAGGED（new 已入库，旧行共存）分别处理。
     *  M2:KEEP_CUSTOM(自定义合并)——survivor=old 首行,value 改 customValue + 重 embed,丢 new/多余 old。
     *  customValue 仅 KEEP_CUSTOM 用;其它 decision 传 null。 */
    @Transactional
    public boolean resolve(Long userId, Long conflictId, String decision, String customValue) {
        MemoryConflict c = conflictMapper.findByIdScalars(conflictId);
        if (c == null || !c.getUserId().equals(userId)) return false;
        if (!"PENDING".equals(c.getStatus()) && !"FLAGGED".equals(c.getStatus())) return false;
        if (!List.of("KEEP_NEW", "KEEP_OLD", "KEEP_BOTH", "DISCARD", "KEEP_CUSTOM").contains(decision)) return false;
        // KEEP_CUSTOM 须有非空 customValue + 至少一行 old 作 survivor
        if ("KEEP_CUSTOM".equals(decision) && (customValue == null || customValue.isBlank())) return false;
        try {
            List<Long> oldIds = parseCsv(conflictMapper.getExistingIdsCsv(c.getId()));
            if ("PENDING".equals(c.getStatus())) {
                // new 尚未入库（快照）
                Map<String, Object> snap = readSnap(c.getId());
                String halfvec = conflictMapper.getNewEmbeddingText(c.getId());
                // V35 修「保留新→冲突不存在」：insertSnap 落新行前先从 old 行继承 scope（home + 可见性 + 项目），
                //   否则 buildFromSnap 默认 home=null(global)，与既有 global 同 key clean 行撞 uk_user_memories_user_key_home
                //   → DuplicateKeyException → @Transactional 整体回滚（hardDelete 也回滚）→ resolve 返 false。
                //   必须在 hardDelete 前读 old 行（删完就查不到了）。
                List<UserMemory> oldRows = (oldIds == null || oldIds.isEmpty()) ? List.of() : memoryMapper.selectBatchIds(oldIds);
                UserMemory ref = oldRows.isEmpty() ? null : oldRows.get(0);
                Long snapHome = ref == null ? null : ref.getHomeProjectId();
                Boolean snapGlobal = ref == null ? Boolean.TRUE : ref.getIsGlobal();
                List<Long> snapProjIds = ref == null ? List.of() : memoryMapper.findProjectIdsByMemory(ref.getId());
                switch (decision) {
                    // KEEP_NEW 须先删 old 再插 new（同 user 同 home 同 key 唯一）；新行继承 old scope
                    case "KEEP_NEW" -> { hardDelete(oldIds); insertSnapScoped(c, snap, halfvec, snapHome, snapGlobal, snapProjIds); }
                    // KEEP_BOTH 合并成一条 clean：old 同 key 行 value 追加 new 值（去重），清 conflict_id，删多余同 key 行
                    case "KEEP_BOTH" -> mergePendingKeepBoth(c, snap, oldIds);
                    case "KEEP_OLD" -> { /* 丢新，旧留 clean */ }
                    case "DISCARD" -> hardDelete(oldIds);
                    // M2 自定义合并:survivor=old 首行,value 改 customValue + 重 embed,删多余 old,丢 snap new
                    case "KEEP_CUSTOM" -> applyCustom(userId, oldIds, customValue);
                }
            } else {
                // FLAGGED：new 已入库，新旧行都带 conflict_id
                List<UserMemory> rows = memoryMapper.findByConflictId(c.getId());
                List<Long> newIds = rows.stream().map(UserMemory::getId)
                        .filter(id -> !oldIds.contains(id)).collect(java.util.stream.Collectors.toList());
                switch (decision) {
                    case "KEEP_NEW" -> { hardDelete(oldIds); memoryMapper.setConflictId(newIds, null); }
                    case "KEEP_OLD" -> { hardDelete(newIds); memoryMapper.setConflictId(oldIds, null); }
                    case "KEEP_BOTH" -> mergeFlaggedKeepBoth(c.getUserId(), c.getId(), oldIds);
                    case "DISCARD" -> {
                        List<Long> all = new ArrayList<>(oldIds); all.addAll(newIds);
                        hardDelete(all);
                    }
                    // M2 自定义合并:survivor=old 首行(兜底 rows 首),value 改 customValue + 重 embed,删 new+多余 old
                    case "KEEP_CUSTOM" -> {
                        Long survivorId = (oldIds == null || oldIds.isEmpty()) ? rows.get(0).getId() : oldIds.get(0);
                        final Long sf = survivorId;
                        List<Long> del = rows.stream().map(UserMemory::getId).filter(id -> !id.equals(sf)).toList();
                        hardDelete(del);
                        memoryMapper.mergeIntoRow(sf, customValue, reembed(customValue), null, null);
                        queryCache.evictUser(userId);
                    }
                }
            }
            conflictMapper.updateStatus(c.getId(), "RESOLVED", decision);
            queryCache.evictUser(userId);
            return true;
        } catch (Exception e) {
            log.warn("resolve 失败 conflictId={}: {}", conflictId, e.getMessage());
            return false;
        }
    }

    /** 落快照新行为 clean（KEEP_NEW / flag / merge 兜底用）。
     *  V35：带 scope（home + 可见性 + 项目挂载），与既有 V34 home 语义对齐——
     *  否则默认 global home 会撞既有 global 同 key clean 唯一约束。 */
    private void insertSnapScoped(MemoryConflict c, Map<String, Object> snap, String halfvec,
                                  Long homeProjectId, Boolean isGlobal, List<Long> projectIds) {
        UserMemory m = buildFromSnap(c.getUserId(), c.getBlockLabel(), snap, homeProjectId, isGlobal);
        // V38 bug 修：anchor 从快照透传（建快照时已 embed），不再传 null 丢召回词袋
        memoryMapper.insertMemory(m, halfvec, (String) snap.get("anchorHalfvec"), (String) snap.get("anchorTokens"));
        if (!Boolean.TRUE.equals(m.getIsGlobal()) && projectIds != null && !projectIds.isEmpty()) {
            memoryMapper.insertMemoryProjects(m.getId(), projectIds);
        }
    }

    // ---- KEEP_BOTH 合并成一条 clean（取代旧"双行共存"，避免同 key 多行脏数据）----

    /** KEEP_BOTH 合并（PENDING 路径）：new 尚未入库（快照），old 行 clean。
     *  同 key old 行 value 追加 new 值（去重 join），survivor 清 conflict_id 变 clean，删多余同 key 行。
     *  无同 key old 行（理论不应发生）→ 兜底 insertSnap 落 new 为 clean。 */
    private void mergePendingKeepBoth(MemoryConflict c, Map<String, Object> snap, List<Long> oldIds) {
        String key = (String) snap.get("key");
        String newValue = (String) snap.get("value");
        List<UserMemory> oldRows = (oldIds == null || oldIds.isEmpty()) ? List.of() : memoryMapper.selectBatchIds(oldIds);
        List<UserMemory> sameKey = oldRows.stream().filter(m -> key.equals(m.getMemoryKey())).toList();
        if (sameKey.isEmpty()) {
            // V35：兜底插新行继承 old scope，避免默认 global 撞唯一约束
            UserMemory ref2 = oldRows.isEmpty() ? null : oldRows.get(0);
            insertSnapScoped(c, snap, conflictMapper.getNewEmbeddingText(c.getId()),
                    ref2 == null ? null : ref2.getHomeProjectId(),
                    ref2 == null ? null : ref2.getIsGlobal(),
                    ref2 == null ? List.of() : memoryMapper.findProjectIdsByMemory(ref2.getId()));
            return;
        }
        Long survivor = sameKey.get(0).getId();
        List<String> values = new ArrayList<>();
        sameKey.forEach(m -> values.add(m.getMemoryValue()));
        values.add(newValue);
        hardDelete(sameKey.stream().map(UserMemory::getId).filter(id -> !id.equals(survivor)).toList());
        mergeValuesInto(c.getUserId(), key, survivor, values, OffsetDateTime.now());
    }

    /** KEEP_BOTH 合并（FLAGGED 路径）：new+old 均带 conflict_id 入库。
     *  survivor = oldIds 首个（原 clean 行），全组值按 [old..., new] 合并，删组内其余行。 */
    private void mergeFlaggedKeepBoth(Long userId, Long conflictId, List<Long> oldIds) {
        List<UserMemory> rows = memoryMapper.findByConflictId(conflictId);
        if (rows.isEmpty()) return;
        Long survivor = (oldIds == null || oldIds.isEmpty()) ? rows.get(0).getId() : oldIds.get(0);
        String key = rows.stream().filter(r -> r.getId().equals(survivor))
                .map(UserMemory::getMemoryKey).findFirst().orElse(null);
        List<String> values = new ArrayList<>();
        rows.stream().filter(m -> oldIds.contains(m.getId())).forEach(m -> values.add(m.getMemoryValue()));
        rows.stream().filter(m -> !oldIds.contains(m.getId())).forEach(m -> values.add(m.getMemoryValue()));
        hardDelete(rows.stream().map(UserMemory::getId).filter(id -> !id.equals(survivor)).toList());
        mergeValuesInto(userId, key, survivor, values, OffsetDateTime.now());
    }

    /** M2 自定义合并(PENDING 路径):survivor=oldIds 首行,value 改 customValue + 重 embed,
     *  清 conflict_id(mergeIntoRow 内置),删多余 old,丢 snap new(不入库)。 */
    private void applyCustom(Long userId, List<Long> oldIds, String customValue) {
        if (oldIds == null || oldIds.isEmpty()) return;
        Long survivorId = oldIds.get(0);
        List<Long> rest = oldIds.stream().filter(id -> !id.equals(survivorId)).toList();
        hardDelete(rest);
        memoryMapper.mergeIntoRow(survivorId, customValue, reembed(customValue), null, null);
        queryCache.evictUser(userId);
        log.info("KEEP_CUSTOM 自定义合并 userId={} survivor={} value={}", userId, survivorId, customValue);
    }

    /** M2:daily_log/日记类 key 天然时序(每日记账),无 key_meta 标也按时间线 merge。 */
    private static boolean isDailyLogKey(String key) {
        if (key == null) return false;
        String k = key.toLowerCase();
        return k.contains("daily_log") || k.contains("diary") || k.contains("journal") || k.endsWith("_log");
    }

    /** 合并值 + re-embed（失败保留旧向量）+ mergeIntoRow（含 updated_at=now()）+ 失效缓存。
     *  M2:按 per-key 时序标分两分支——
     *   - temporal=true:走 {@link MemoryValueTimeline} 时间线(values 末项=new 带 newTs 日期前缀,其余 old 按序拼 ;)。
     *   - temporal=false:维持中文逗号 {@link #joinDistinct}(现状)。
     *  values 顺序约定:[old..., new]——PENDING/FLAGGED 两路径都在末尾 append new。 */
    private void mergeValuesInto(Long userId, String key, Long survivorId, List<String> values, OffsetDateTime newTs) {
        boolean temporal = keyMetaService.isTemporal(userId, key) || isDailyLogKey(key);
        String merged;
        if (temporal) {
            List<String> nonNull = new ArrayList<>();
            for (String v : values) if (v != null && !v.isBlank() && !nonNull.contains(v)) nonNull.add(v);
            if (nonNull.isEmpty()) {
                merged = "";
            } else if (nonNull.size() == 1) {
                // 仅 new(无 old):时序单值也带日期前缀
                merged = MemoryValueTimeline.withDatePrefix(nonNull.get(0), newTs);
            } else {
                String oldConcat = String.join(";", nonNull.subList(0, nonNull.size() - 1));
                String newVal = nonNull.get(nonNull.size() - 1);
                merged = MemoryValueTimeline.mergeTemporal(oldConcat, newVal, newTs);
            }
        } else {
            merged = joinDistinct(values, "，");
        }
        memoryMapper.mergeIntoRow(survivorId, merged, reembed(merged), null, null);
        queryCache.evictUser(userId);
        log.info("KEEP_BOTH 合并 userId={} key={} temporal={} survivor={} merged={}", userId, key, temporal, survivorId, merged);
    }

    private String reembed(String text) {
        try {
            float[] vec = llmGateway.embed(text, RagConfig.MEMORY_EMBED_MODEL);
            return HalfVecUtil.toHalfVec(vec);
        } catch (Exception e) {
            log.warn("KEEP_BOTH 合并 re-embed 失败，保留旧向量: {}", e.getMessage());
            return null;
        }
    }

    private static String joinDistinct(List<String> values, String sep) {
        List<String> parts = new ArrayList<>();
        for (String v : values) {
            if (v != null && !parts.contains(v)) parts.add(v);
        }
        return String.join(sep, parts);
    }

    /** 列用户待处理冲突（PENDING+FLAGGED，分组）。面板可见——PENDING 让用户知道有记忆卡冲突待确认。 */
    public List<MemoryConflictVO> listActive(Long userId) {
        List<MemoryConflict> list = conflictMapper.findActiveByUser(userId);
        List<MemoryConflictVO> out = new ArrayList<>();
        for (MemoryConflict c : list) {
            MemoryConflictVO vo = new MemoryConflictVO();
            vo.setConflictId(c.getId());
            vo.setBlock(c.getBlockLabel());
            vo.setStatus(c.getStatus());
            vo.setAskText(c.getAskText());
            vo.setCreatedAt(c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
            List<MemoryCandidateVO> cands = new ArrayList<>();
            if ("PENDING".equals(c.getStatus())) {
                // PENDING：new 未入库(快照, id=null) + old(existing_memory_ids 查 user_memories)
                try {
                    Map<String, Object> snap = readSnap(c.getId());
                    MemoryCandidateVO newCand = new MemoryCandidateVO();
                    newCand.setId(null);
                    newCand.setCategory((String) snap.get("category"));
                    newCand.setMemoryKey((String) snap.get("key"));
                    newCand.setMemoryValue((String) snap.get("value"));
                    cands.add(newCand);
                } catch (Exception e) {
                    log.warn("listActive 读 PENDING 快照失败 conflictId={}: {}", c.getId(), e.getMessage());
                }
                List<Long> oldIds = parseCsv(conflictMapper.getExistingIdsCsv(c.getId()));
                if (!oldIds.isEmpty()) {
                    for (UserMemory m : memoryMapper.selectBatchIds(oldIds)) cands.add(toCand(m));
                }
            } else {
                // FLAGGED：新旧行均带 conflict_id 入库（见 flag()），直接查全组真实 id
                for (UserMemory m : memoryMapper.findByConflictId(c.getId())) cands.add(toCand(m));
            }
            vo.setCandidates(cands);
            out.add(vo);
        }
        return out;
    }

    // ---- helpers ----

    private Map<String, Object> readSnap(Long conflictId) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> snap = objectMapper.readValue(conflictMapper.getNewMemoryText(conflictId), Map.class);
        return snap;
    }

    /** 快照 → JSON map（LinkedHashMap 容 null 值：keyZh/entities/anchor 老快照或空事实可能为 null，Map.of 拒 null 会抛）。
     *  V38：补 keyZh/entities/anchorHalfvec/anchorTokens，下游 buildFromSnap/insertSnapScoped 透传不丢字段。 */
    private Map<String, Object> snapToMap(ExtractedFactSnapshot snap) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("category", snap.category());
        map.put("key", snap.key());
        map.put("keyZh", snap.keyZh());
        map.put("value", snap.value());
        map.put("confidence", snap.confidence());
        map.put("entities", snap.entitiesJson());
        map.put("anchorHalfvec", snap.anchorHalfvec());
        map.put("anchorTokens", snap.anchorTokens());
        return map;
    }

    private void hardDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        memoryMapper.deleteBatchIds(ids);
    }

    private UserMemory buildFromSnap(Long userId, String block, Map<String, Object> snap,
                                     Long homeProjectId, Boolean isGlobal) {
        UserMemory m = new UserMemory();
        m.setUserId(userId);
        m.setCategory((String) snap.get("category"));
        m.setMemoryKey((String) snap.get("key"));
        m.setMemoryKeyZh((String) snap.get("keyZh"));
        m.setMemoryValue((String) snap.get("value"));
        m.setSource("INFERRED");
        m.setConfidence(new BigDecimal(String.valueOf(snap.get("confidence"))));
        m.setBlockLabel(block);
        m.setEntities((String) snap.get("entities"));
        // V35：落 home + 可见性，否则默认 global 撞既有 global 同 key 唯一约束
        m.setHomeProjectId(homeProjectId);
        m.setIsGlobal(isGlobal == null ? Boolean.TRUE : isGlobal);
        return m;
    }

    private MemoryCandidateVO toCand(UserMemory m) {
        MemoryCandidateVO v = new MemoryCandidateVO();
        v.setId(m.getId());
        v.setMemoryKey(m.getMemoryKey());
        v.setMemoryValue(m.getMemoryValue());
        v.setCategory(m.getCategory());
        return v;
    }

    private static List<Long> parseCsv(String csv) {
        List<Long> ids = new ArrayList<>();
        if (csv == null || csv.isBlank()) return ids;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                try { ids.add(Long.parseLong(t)); } catch (NumberFormatException ignore) {}
            }
        }
        return ids;
    }

    /** 快照值对象（从 ExtractedFact + halfvec + anchor 组装）。
     *  V38 bug 修：补 keyZh + entitiesJson + anchor 两列——否则 KEEP_NEW/flag/createFlagged
     *  物化新行时这俩字段（+ anchor 召回词袋）全丢，survivor 行 entities/memory_key_zh 变空。 */
    public record ExtractedFactSnapshot(String category, String key, String keyZh, String value,
                                        String confidence, String halfvec,
                                        String entitiesJson, String anchorHalfvec, String anchorTokens) {}
}
