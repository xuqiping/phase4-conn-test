package com.superprogrammer.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.dto.MemoryCandidateVO;
import com.superprogrammer.chat.dto.MemoryConflictVO;
import com.superprogrammer.chat.entity.MemoryConflict;
import com.superprogrammer.chat.entity.UserMemory;
import com.superprogrammer.chat.mapper.MemoryConflictMapper;
import com.superprogrammer.chat.mapper.UserMemoryMapper;
import com.superprogrammer.knowledge.service.RagConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
            c.setNewMemory(objectMapper.writeValueAsString(Map.of(
                    "category", snap.category(), "key", snap.key(),
                    "value", snap.value(), "confidence", snap.confidence())));
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
            UserMemory m = buildFromSnap(c.getUserId(), c.getBlockLabel(), snap);
            memoryMapper.insertMemory(m, halfvec);
            ids.add(m.getId());
            memoryMapper.setConflictId(ids, c.getId());
            conflictMapper.updateStatus(c.getId(), "FLAGGED", "FLAGGED");
        } catch (Exception e) {
            log.warn("flag 失败 conflictId={}: {}", c.getId(), e.getMessage());
        }
    }

    /** 执行用户决定。PENDING（new 未入库）与 FLAGGED（new 已入库，旧行共存）分别处理。 */
    @Transactional
    public boolean resolve(Long userId, Long conflictId, String decision) {
        MemoryConflict c = conflictMapper.findByIdScalars(conflictId);
        if (c == null || !c.getUserId().equals(userId)) return false;
        if (!List.of("KEEP_NEW", "KEEP_OLD", "KEEP_BOTH", "DISCARD").contains(decision)) return false;
        try {
            List<Long> oldIds = parseCsv(conflictMapper.getExistingIdsCsv(c.getId()));
            if ("PENDING".equals(c.getStatus())) {
                // new 尚未入库（快照）
                Map<String, Object> snap = readSnap(c.getId());
                String halfvec = conflictMapper.getNewEmbeddingText(c.getId());
                switch (decision) {
                    case "KEEP_NEW" -> { insertSnap(c, snap, halfvec); hardDelete(oldIds); }
                    case "KEEP_BOTH" -> insertSnap(c, snap, halfvec);   // clean
                    case "KEEP_OLD" -> { /* 丢新，旧留 */ }
                    case "DISCARD" -> hardDelete(oldIds);
                }
            } else {
                // FLAGGED：new 已入库，新旧行都带 conflict_id
                List<UserMemory> rows = memoryMapper.findByConflictId(c.getId());
                List<Long> newIds = rows.stream().map(UserMemory::getId)
                        .filter(id -> !oldIds.contains(id)).collect(java.util.stream.Collectors.toList());
                switch (decision) {
                    case "KEEP_NEW" -> { hardDelete(oldIds); memoryMapper.setConflictId(newIds, null); }
                    case "KEEP_OLD" -> { hardDelete(newIds); memoryMapper.setConflictId(oldIds, null); }
                    case "KEEP_BOTH" -> {   // 都留，清冲突标
                        List<Long> all = new ArrayList<>(oldIds); all.addAll(newIds);
                        memoryMapper.setConflictId(all, null);
                    }
                    case "DISCARD" -> {
                        List<Long> all = new ArrayList<>(oldIds); all.addAll(newIds);
                        hardDelete(all);
                    }
                }
            }
            conflictMapper.updateStatus(c.getId(), "RESOLVED", decision);
            return true;
        } catch (Exception e) {
            log.warn("resolve 失败 conflictId={}: {}", conflictId, e.getMessage());
            return false;
        }
    }

    private void insertSnap(MemoryConflict c, Map<String, Object> snap, String halfvec) {
        UserMemory m = buildFromSnap(c.getUserId(), c.getBlockLabel(), snap);
        memoryMapper.insertMemory(m, halfvec);
    }

    /** 列用户 FLAGGED 冲突（分组）。 */
    public List<MemoryConflictVO> listFlagged(Long userId) {
        List<MemoryConflict> list = conflictMapper.findFlaggedByUser(userId);
        List<MemoryConflictVO> out = new ArrayList<>();
        for (MemoryConflict c : list) {
            MemoryConflictVO vo = new MemoryConflictVO();
            vo.setConflictId(c.getId());
            vo.setBlock(c.getBlockLabel());
            vo.setStatus(c.getStatus());
            vo.setAskText(c.getAskText());
            vo.setCreatedAt(c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
            // FLAGGED：新旧行均带 conflict_id 入库（见 flag()），直接查 DB 取全组真实 id（修旧版新候选 id=null）
            List<MemoryCandidateVO> cands = new ArrayList<>();
            for (UserMemory m : memoryMapper.findByConflictId(c.getId())) cands.add(toCand(m));
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

    private void hardDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        memoryMapper.deleteBatchIds(ids);
    }

    private UserMemory buildFromSnap(Long userId, String block, Map<String, Object> snap) {
        UserMemory m = new UserMemory();
        m.setUserId(userId);
        m.setCategory((String) snap.get("category"));
        m.setMemoryKey((String) snap.get("key"));
        m.setMemoryValue((String) snap.get("value"));
        m.setSource("INFERRED");
        m.setConfidence(new BigDecimal(String.valueOf(snap.get("confidence"))));
        m.setBlockLabel(block);
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

    /** 快照值对象（从 ExtractedFact + halfvec 组装）。 */
    public record ExtractedFactSnapshot(String category, String key, String value,
                                        String confidence, String halfvec) {}
}
