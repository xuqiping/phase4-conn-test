package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.chat.service.internal.MemoryGenerator.GenResult;
import com.superprogrammer.chat.service.internal.MemoryGenerator.SideLayers;
import com.superprogrammer.chat.service.internal.MemoryPrefilter.FilterResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 计划12 · E · raw turn 补 tag 回填（总体设计 §3.4 line 121/132「手动总结先 backfill raw」）。
 * <p>
 * 手动总结入口对 scope 内 {@code gen_done=false} 的 raw turn 先补跑生成 + 标签归一，分批 ≤20/批，
 * 再进压缩。<b>仅手动总结触发</b>——定时路径不 backfill raw、不调生成 LLM（gen 关态空跳过，
 * 设计 §3.4 line 125 解耦理由：总结低频 vs gen 高频不应强绑）。
 * <p>
 * <b>复用 C 组件</b>（不重写生成链）：{@link MemoryPrefilter} 单侧过滤（null 侧自动 skip）+
 * {@link MemoryGenerator} 单侧三层 + {@link MemoryTagResolver} 写时归一。
 * <p>
 * <b>降级</b>：prefilter 两跳 / LLM 失败 / side 无核心(topic+label) / resolve 返 null →
 * 仍 {@code applyBackfill} 置 {@code gen_done=true}（空 tag），<b>不再进 backfill</b>（防死循环重试）。
 * 空 tag turn 不进召回（无 tag 不聚合）、不计告警阈值（gen_done=true 但无 coverage 行——设计 §3.9
 * line 178 未总结 = gen_done=true 且无 coverage；空 tag turn 无 tag 进不了 coverage，仍算未覆盖，
 * 但压缩时被「无 tag 不单独成组」自然跳过）。gen 关用户的 raw 想总结只能走手动（本类）。
 *
 * @see MemoryGenerationService C 写入链（对话流 fire-and-forget，本类是其手动补跑镜像）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryBackfillService {

    /** 每批回填上限（设计 §3.4「≤20/批」）。 */
    public static final int BATCH_SIZE = 20;

    private final MemoryPrefilter prefilter;
    private final MemoryGenerator generator;
    private final MemoryTagResolver tagResolver;
    private final MemoryTurnMapper turnMapper;

    /**
     * 回填 scope 内全部 raw turn（分批 ≤20），同步阻塞——由 MemoryConsolidationService 在 worker/手动
     * 线程内调用。personalScope=true → 个人 raw（born_personal=true）；否则项目 raw（project_ids 含 X）。
     *
     * @return 处理 turn 数（含「无可提取事实」置 gen_done=true 的）
     */
    public int backfillScope(Long userId, Long projectId, boolean personalScope) {
        int processed = 0;
        int guard = 0;  // 兜底防异常导致死循环（applyBackfill 失败时 raw 不出 batch）
        while (guard++ < 1000) {
            List<MemoryTurn> raws = turnMapper.findRawTurnsForBackfill(userId, projectId, personalScope, BATCH_SIZE);
            if (raws == null || raws.isEmpty()) {
                break;
            }
            for (MemoryTurn raw : raws) {
                backfillOne(userId, raw);
                processed++;
            }
        }
        if (processed > 0) {
            log.info("backfill 完成 userId={} scope={} personal={} 处理 {} 条 raw",
                    userId, projectId, personalScope, processed);
        }
        return processed;
    }

    /** 回填单条 raw turn：单侧过滤 → 单侧生成 → 标签归一 → applyBackfill（失败/无事实仍置 gen_done=true）。 */
    void backfillOne(Long userId, MemoryTurn raw) {
        boolean isInput = "INPUT".equals(raw.getDirection());
        // 单侧喂入：另一侧 null → prefilter 自动 skip；generator 按非跳过侧建单侧 prompt
        String userInput = isInput ? raw.getRawContent() : null;
        String assistantOutput = isInput ? null : raw.getRawContent();

        FilterResult filter = prefilter.filter(userInput, assistantOutput);
        if (filter.bothSkipped()) {
            log.debug("backfill turn {} 两侧均被前置过滤跳过 → 置 gen_done=true 空 tag", raw.getId());
            turnMapper.applyBackfill(raw.getId(), List.of(), null, null, userId);
            return;
        }

        GenResult gen = generator.generate(userId, userInput, assistantOutput, filter);
        SideLayers side = extractSide(gen, isInput);
        if (side == null || !hasCore(side)) {
            log.debug("backfill turn {} 无可提取事实（gen={}）→ 置 gen_done=true 空 tag", raw.getId(),
                    gen == null ? "null" : "empty");
            turnMapper.applyBackfill(raw.getId(), List.of(), null, null, userId);
            return;
        }

        Long tagId = tagResolver.resolve(userId, side.subject(), side.topic(), side.label());
        String l1 = side.l1Summary() == null ? "" : side.l1Summary();
        String l2 = side.l2Detail() == null ? "" : side.l2Detail();
        turnMapper.applyBackfill(raw.getId(), tagId != null ? List.of(tagId) : List.of(), l1, l2, userId);
        log.debug("backfill turn {} → tagId={} l1.len={}", raw.getId(), tagId, l1.length());
    }

    private static SideLayers extractSide(GenResult gen, boolean isInput) {
        if (gen == null) {
            return null;
        }
        return isInput ? gen.input() : gen.output();
    }

    private static boolean hasCore(SideLayers s) {
        return s != null
                && s.topic() != null && !s.topic().isBlank()
                && s.label() != null && !s.label().isBlank();
    }
}
