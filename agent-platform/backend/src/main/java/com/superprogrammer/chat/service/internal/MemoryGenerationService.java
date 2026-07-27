package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 计划12 · C · 记忆生成写入编排器（总体设计 §3.1 + §3.2）。
 * <p>
 * 流式回复后异步 fire-and-forget 落两条 turn（INPUT/OUTPUT 各一）。编排链：
 * <pre>
 *   前置过滤(按侧) → gen 开关 → [开] 生成 LLM(双侧三层) → 标签归一 → 写 turn(gen_done=true)
 *                                          ↘ [关/失败] 写 raw turn(gen_done=false)
 *   两侧均被前置过滤跳过 → 不调 LLM 不写 raw。
 * </pre>
 * <p>
 * <b>写入目标（与召回解耦，默认 {个人}）</b>：
 * <ul>
 *   <li>{@code born_personal} 写入时定死：勾个人 → true；仅项目 → false；<b>卸空(无项目) → 自动转 true</b>（防无归属）。</li>
 *   <li>{@code project_ids} 叠加共享（挂哪些项目就哪些成员经 ACL 可读）。</li>
 *   <li>非项目会话（session projectId=null）无项目可选 → 恒 {个人}。</li>
 *   <li>权限校验在控制器层（写入目标端点 {@code project_ids} 校验 accessible，向量 15）。</li>
 * </ul>
 * <p>
 * <b>缓存</b>：写入后立即 {@code evictUser(userId)}（向量 9，不等 TTL）。
 * <p>
 * <b>偏离 plan</b>：plan 列「改 MemoryService.java」承载写入链——MemoryService 是 1278 行 legacy
 * {@code user_memories} 逻辑，新链基 {@code memory_turns} 新表。混入 = god-class + 新旧纠缠。
 * 故新建本类独立编排，legacy MemoryService 待 H 收尾整块废（设计 §9：旧端点迁移后 404）。
 *
 * @see MemoryPrefilter 前置过滤
 * @see MemoryGenToggleService gen 开关
 * @see MemoryGenerator 双侧三层生成
 * @see MemoryTagResolver 写时标签归一
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryGenerationService {

    private static final String DIR_INPUT = "INPUT";
    private static final String DIR_OUTPUT = "OUTPUT";

    private final MemoryPrefilter prefilter;
    private final MemoryGenToggleService toggleService;
    private final MemoryGenerator generator;
    private final MemoryTagResolver tagResolver;
    private final MemoryTurnMapper turnMapper;
    private final MemoryQueryCache queryCache;

    /** 按 bean 名注入（同 MemoryService / ChatSessionService 范式）。 */
    private final TaskExecutor memoryTaskExecutor;

    /**
     * 异步入口（fire-and-forget）：提交到 memoryTaskExecutor 立即返回，不阻塞对话。
     * 队列满被拒 → 降级日志（同 ChatSessionService RejectedExecution 处理范式）。
     *
     * @param userId                作者
     * @param sessionId             会话 id
     * @param sessionProjectId      会话所属项目（null = 非项目会话）
     * @param writePersonal         写入目标是否含个人（默认 true）
     * @param writeTargetProjectIds 写入目标项目集（已校验 accessible；非项目会话忽略）
     * @param userInput             用户本轮输入
     * @param assistantOutput       助手本轮回复
     */
    public void processTurnAsync(Long userId, Long sessionId, Long sessionProjectId,
                                 boolean writePersonal, List<Long> writeTargetProjectIds,
                                 String userInput, String assistantOutput) {
        final List<Long> targets = writeTargetProjectIds == null ? List.of() : List.copyOf(writeTargetProjectIds);
        final String input = userInput;
        final String output = assistantOutput;
        try {
            memoryTaskExecutor.execute(() -> processTurn(userId, sessionId, sessionProjectId,
                    writePersonal, targets, input, output));
        } catch (org.springframework.core.task.TaskRejectedException e) {
            log.warn("记忆生成任务被拒(队列满) userId={} sessionId={}: {}", userId, sessionId, e.getMessage());
        }
    }

    /**
     * 同步处理一轮对话（在 executor 线程跑）。包可见供 IT 直调。
     *
     * @return 写入的 turn 数（0 / 1 / 2）
     */
    int processTurn(Long userId, Long sessionId, Long sessionProjectId,
                    boolean writePersonal, List<Long> writeTargetProjectIds,
                    String userInput, String assistantOutput) {
        MemoryPrefilter.FilterResult filter = prefilter.filter(userInput, assistantOutput);
        if (filter.bothSkipped()) {
            log.debug("两侧均被前置过滤跳过 userId={} → 不调 LLM 不写 raw", userId);
            return 0;
        }

        boolean genOn = toggleService.resolveGenEnabled(userId, sessionProjectId);
        MemoryGenerator.GenResult gen = genOn
                ? generator.generate(userId, userInput, assistantOutput, filter)
                : null;
        if (genOn && gen == null) {
            log.info("生成 LLM 失败 userId={} → 过过滤侧写 raw(gen_done=false) 降级", userId);
        }

        // 写入目标计算（与召回解耦）
        List<Long> projectIds;
        boolean bornPersonal;
        if (sessionProjectId == null) {
            // 非项目会话：无项目可选，恒个人出身
            projectIds = List.of();
            bornPersonal = true;
        } else {
            projectIds = normalizeProjectIds(writeTargetProjectIds);
            bornPersonal = writePersonal || projectIds.isEmpty();  // 卸空转个人出身
        }

        int written = 0;
        if (!filter.skipInput()) {
            written += writeTurn(userId, sessionId, DIR_INPUT, userInput,
                    gen != null ? gen.input() : null, bornPersonal, projectIds);
        }
        if (!filter.skipOutput()) {
            written += writeTurn(userId, sessionId, DIR_OUTPUT, assistantOutput,
                    gen != null ? gen.output() : null, bornPersonal, projectIds);
        }

        // 写入 → 召回集变，立即 evict（向量 9）
        queryCache.evictUser(userId);
        log.info("记忆写入完成 userId={} sessionId={} genOn={} written={} bornPersonal={} projectIds={}",
                userId, sessionId, genOn, written, bornPersonal, projectIds);
        return written;
    }

    /** 写一条 turn：有生成层 → tag 归一 + L1/L2 + gen_done=true；无 → 仅 raw + gen_done=false。 */
    private int writeTurn(Long userId, Long sessionId, String direction, String rawText,
                          MemoryGenerator.SideLayers layers, boolean bornPersonal, List<Long> projectIds) {
        MemoryTurn t = new MemoryTurn();
        t.setUserId(userId);
        t.setSessionId(sessionId);
        t.setDirection(direction);
        t.setRawContent(rawText);
        t.setBornPersonal(bornPersonal);
        t.setProjectIds(projectIds);
        t.setDepartedProjectIds(List.of());
        t.setDeletedProjectIds(List.of());
        // 审计字段显式置（异步线程无请求上下文，MetaObjectHandler 可能不填）
        t.setCreatedBy(userId);
        t.setUpdatedBy(userId);

        if (layers != null) {
            Long tagId = tagResolver.resolve(userId, layers.subject(), layers.topic(), layers.label());
            t.setTagIds(tagId != null ? List.of(tagId) : List.of());
            t.setL1Summary(layers.l1Summary());
            t.setL2Detail(layers.l2Detail());
            t.setGenDone(true);
        } else {
            t.setTagIds(List.of());
            t.setGenDone(false);  // 仅 raw（gen 关 / 生成失败 / 侧被过滤）
        }

        turnMapper.insert(t);
        log.debug("写 turn userId={} dir={} id={} genDone={} tagId={}",
                userId, direction, t.getId(), t.getGenDone(),
                (layers != null && t.getTagIds() != null && !t.getTagIds().isEmpty() ? t.getTagIds().get(0) : null));
        return 1;
    }

    /** 去重 + null 安全（保留顺序）。 */
    private static List<Long> normalizeProjectIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> dedup = new LinkedHashSet<>(ids.size());
        for (Long id : ids) {
            if (id != null) {
                dedup.add(id);
            }
        }
        return new ArrayList<>(dedup);
    }
}
