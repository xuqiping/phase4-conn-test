package com.superprogrammer.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.UserMemoryVO;
import com.superprogrammer.chat.entity.UserMemory;
import com.superprogrammer.chat.mapper.UserMemoryMapper;
import com.superprogrammer.chat.service.internal.ExtractedFact;
import com.superprogrammer.chat.service.internal.JudgeResult;
import com.superprogrammer.chat.service.internal.MemoryBlockClassifier;
import com.superprogrammer.chat.service.internal.MemoryConflictJudge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户长期记忆服务（V27 重构）。
 * 记忆模式 ON 时由 ChatSessionService 同步调用 processMemory：
 * 抽取 K 事实 → 每条 embed 归块 → 块非空则 LLM 冲突判定 →
 *   无冲突直插 clean / 有冲突建 PENDING（锁会话，askText 追加回复）/ 锁忙降级。
 * buildMemoryContext 注入时对 FLAGGED 行加 [⚠️冲突] 前缀 + counterpart。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final UserMemoryMapper memoryMapper;
    private final MemoryBlockClassifier classifier;
    private final MemoryConflictJudge judge;
    private final MemoryConflictService conflictService;

    /**
     * 同步处理记忆（记忆模式 ON，回复生成后、持久化前调）。
     * @return askText（有冲突且建了 PENDING），null=无。ChatSessionService 把它追加进同一轮回复。
     */
    public String processMemory(Long userId, Long sessionId, String userMessage, String assistantResponse) {
        List<ExtractedFact> facts;
        try {
            facts = judge.extract(userMessage, assistantResponse);
        } catch (Exception e) {
            log.warn("记忆抽取失败 userId={}: {}", userId, e.getMessage());
            return null;
        }
        if (facts == null || facts.isEmpty()) return null;

        String askText = null;
        for (ExtractedFact f : facts) {
            try {
                String factText = f.key() + ":" + f.value();
                MemoryBlockClassifier.BlockResult br = classifier.classify(userId, factText, f.block());
                List<UserMemory> members = memoryMapper.findCleanByBlock(userId, br.blockLabel());
                if (members.isEmpty()) {
                    insertClean(userId, f, br);          // 新块，无冲突
                    continue;
                }
                List<JudgeResult> jr = judge.judge(List.of(f), members);
                JudgeResult r = (jr == null || jr.isEmpty()) ? null : jr.get(0);
                if (r == null || !r.conflict()) {
                    insertClean(userId, f, br);          // 同块但不矛盾
                    continue;
                }
                // 冲突：会话锁空→PENDING；锁忙→降级 clean（保证不丢事实，留阶段7 完善 flag）
                var pending = conflictService.getActivePendingOrExpire(sessionId, userId);
                if (pending == null) {
                    List<Long> ids = (r.conflictingIds() == null || r.conflictingIds().isEmpty())
                            ? members.stream().map(UserMemory::getId).collect(Collectors.toList())
                            : r.conflictingIds();
                    var snap = new MemoryConflictService.ExtractedFactSnapshot(
                            f.category(), f.key(), f.value(), f.confidence().toPlainString(), br.halfvec());
                    conflictService.createPending(userId, sessionId, br.blockLabel(), snap, ids, r.askText());
                    if (askText == null) askText = r.askText();   // 多事实只取首个 ask
                } else {
                    log.warn("会话 {} 记忆锁忙，事实 {} 降级 clean 入库（不打标）", sessionId, f.key());
                    insertClean(userId, f, br);
                }
            } catch (Exception e) {
                log.warn("processMemory 单事实处理失败 userId={} key={}: {}", userId, f.key(), e.getMessage());
            }
        }
        return askText;
    }

    private void insertClean(Long userId, ExtractedFact f, MemoryBlockClassifier.BlockResult br) {
        UserMemory m = new UserMemory();
        m.setUserId(userId);
        m.setCategory(f.category());
        m.setMemoryKey(f.key());
        m.setMemoryValue(f.value());
        m.setSource("INFERRED");
        m.setConfidence(f.confidence());
        m.setBlockLabel(br.blockLabel());
        memoryMapper.insertMemory(m, br.halfvec());
    }

    // ============================ 注入（下游 LLM system msg）============================

    public String buildMemoryContext(Long userId) {
        List<UserMemory> memories = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .ge(UserMemory::getConfidence, new BigDecimal("0.5"))
                .orderByDesc(UserMemory::getUpdatedAt));
        if (memories.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (UserMemory m : memories) {
            if (m.getConflictId() != null) {
                String counterpart = findCounterpartValue(userId, m.getConflictId(), m.getId());
                sb.append("[⚠️冲突] ").append(formatLine(m))
                  .append(" （与\"").append(counterpart).append("\"冲突，待澄清）\n");
            } else {
                sb.append(formatLine(m)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String formatLine(UserMemory m) {
        return "[" + m.getCategory() + "] " + m.getMemoryKey() + ": " + m.getMemoryValue();
    }

    private String findCounterpartValue(Long userId, Long conflictId, Long selfId) {
        List<UserMemory> same = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getConflictId, conflictId)
                .ne(UserMemory::getId, selfId));
        if (same.isEmpty()) return "?";
        return same.stream().map(UserMemory::getMemoryValue).collect(Collectors.joining("/"));
    }

    // ============================ 用户自服务（查询/删除）============================

    /** 列出当前用户全部记忆（按 updatedAt 倒序），带冲突标志字段。 */
    public List<UserMemoryVO> listMemories(Long userId) {
        List<UserMemory> memories = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .orderByDesc(UserMemory::getUpdatedAt));
        return memories.stream().map(m -> toVO(userId, m)).collect(Collectors.toList());
    }

    /** 删除单条记忆（ownership 校验：非本人返回 false）。 */
    public boolean deleteMemory(Long userId, Long id) {
        UserMemory m = memoryMapper.selectById(id);
        if (m == null || !userId.equals(m.getUserId())) {
            return false;
        }
        return memoryMapper.deleteById(id) > 0;
    }

    /** 清空当前用户全部记忆，返回删除条数。 */
    public int clearMemories(Long userId) {
        return memoryMapper.delete(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId));
    }

    private UserMemoryVO toVO(Long userId, UserMemory m) {
        UserMemoryVO vo = new UserMemoryVO();
        vo.setId(m.getId());
        vo.setCategory(m.getCategory());
        vo.setMemoryKey(m.getMemoryKey());
        vo.setMemoryValue(m.getMemoryValue());
        vo.setSource(m.getSource());
        vo.setConfidence(m.getConfidence());
        vo.setCreatedAt(m.getCreatedAt());
        vo.setUpdatedAt(m.getUpdatedAt());
        vo.setConflictId(m.getConflictId());
        vo.setConflictStatus(m.getConflictId() != null ? "FLAGGED" : null);
        vo.setConflictWith(m.getConflictId() != null ? findCounterpartValue(userId, m.getConflictId(), m.getId()) : null);
        return vo;
    }
}
