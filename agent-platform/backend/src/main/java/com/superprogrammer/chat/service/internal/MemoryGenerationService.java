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
 * <b>二期 P1 定案（FR-006，V67）</b>：turns <b>纯个人域</b>——个人对话全量进个人流水账，
 * 无「写入目标」概念（一期 writePersonal/writeTargetProjectIds 参数链已删）；项目记忆改走
 * {@code memory_project_entries} 收录规则路由（本类尾部挂 {@link MemoryRoutingService} 钩子）。
 * gen 开关恒读全局个人兜底（{@code rag.memory.gen.personal.enabled}），项目级收录开关在路由层判定。
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
 * @see MemoryRoutingService 二期 P1 项目收录路由（尾部 fire-and-forget 钩子）
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
    private final com.superprogrammer.chat.mapper.MemoryTagMapper tagMapper;
    private final MemoryQueryCache queryCache;
    private final MemoryRoutingService routingService;
    private final com.superprogrammer.system.service.SystemSettingService systemSettingService;

    /** 按 bean 名注入（同 MemoryService / ChatSessionService 范式）。 */
    private final TaskExecutor memoryTaskExecutor;

    /**
     * 异步入口（fire-and-forget）：提交到 memoryTaskExecutor 立即返回，不阻塞对话。
     * 队列满被拒 → 降级日志（同 ChatSessionService RejectedExecution 处理范式）。
     *
     * @param userId          作者
     * @param sessionId       会话 id
     * @param userInput       用户本轮输入
     * @param assistantOutput 助手本轮回复
     * @param chatModel       本轮对话所选 model（落库 chat_model，并下沉给路由/生成；null 回退默认）
     */
    public void processTurnAsync(Long userId, Long sessionId,
                                 String userInput, String assistantOutput, String chatModel) {
        final String input = userInput;
        final String output = assistantOutput;
        final String model = chatModel;
        try {
            memoryTaskExecutor.execute(() -> processTurn(userId, sessionId, input, output, model));
        } catch (org.springframework.core.task.TaskRejectedException e) {
            log.warn("记忆生成任务被拒(队列满) userId={} sessionId={}: {}", userId, sessionId, e.getMessage());
        }
    }

    /**
     * 同步处理一轮对话（在 executor 线程跑）。包可见供 IT 直调。
     *
     * @return 写入的 turn 数（0 / 1 / 2）
     */
    int processTurn(Long userId, Long sessionId, String userInput, String assistantOutput, String chatModel) {
        MemoryPrefilter.FilterResult filter = prefilter.filter(userInput, assistantOutput);
        if (filter.bothSkipped()) {
            log.debug("两侧均被前置过滤跳过 userId={} → 不调 LLM 不写 raw", userId);
            return 0;
        }

        // 二期 P1：turns 纯个人域 → gen 开关恒读全局个人兜底（项目级开关在路由收录层判定）
        boolean genOn = toggleService.resolveGenEnabled(userId, null);
        // 生成用 effective model：对话所选优先，null 回退可配默认（chatModel 原值仍落库 turn.chat_model）
        String genModel = (chatModel != null && !chatModel.isBlank())
                ? chatModel : systemSettingService.getMemoryJudgeModel();
        // 大类词表（V77）：base vocab ∪ 用户已批准 topic，约束 topic 落大类，杜绝细标签
        java.util.Set<String> effectiveVocab = buildEffectiveVocab(userId);
        MemoryGenerator.GenResult gen = genOn
                ? generator.generate(userId, userInput, assistantOutput, filter, genModel, effectiveVocab)
                : null;
        if (genOn && gen == null) {
            log.info("生成 LLM 失败 userId={} → 过过滤侧写 raw(gen_done=false) 降级", userId);
        }

        int written = 0;
        MemoryTurn inputTurn = null;
        MemoryTurn outputTurn = null;
        if (!filter.skipInput()) {
            inputTurn = writeTurn(userId, sessionId, DIR_INPUT, userInput,
                    gen != null ? gen.input() : null, chatModel);
            written += inputTurn != null ? 1 : 0;
        }
        if (!filter.skipOutput()) {
            outputTurn = writeTurn(userId, sessionId, DIR_OUTPUT, assistantOutput,
                    gen != null ? gen.output() : null, chatModel);
            written += outputTurn != null ? 1 : 0;
        }

        // 写入 → 召回集变，立即 evict（向量 9）
        queryCache.evictUser(userId);
        log.info("记忆写入完成 userId={} sessionId={} genOn={} written={}",
                userId, sessionId, genOn, written);

        // 记忆二期 P1 · 项目收录路由（fire-and-forget 钩子，异常在 RoutingService 内自吞）：
        // 仅 gen_done 的轮次参与（路由粗筛要 L1+tags）；双侧 L1 合并送路由，source_turn 优先 OUTPUT 侧。
        if ((inputTurn != null && Boolean.TRUE.equals(inputTurn.getGenDone()))
                || (outputTurn != null && Boolean.TRUE.equals(outputTurn.getGenDone()))) {
            routingService.routeAsync(buildRoutingInput(userId, sessionId, inputTurn, outputTurn, chatModel));
        }
        return written;
    }

    /** 组装路由入参：双侧 L1/L2 合并（单侧 null 容忍），tag_ids 取并集，source_turn 优先 OUTPUT。 */
    private MemoryRoutingService.RoutingInput buildRoutingInput(Long userId, Long sessionId,
                                                                MemoryTurn inputTurn, MemoryTurn outputTurn,
                                                                String chatModel) {
        String l1 = joinNonBlank(inputTurn != null ? inputTurn.getL1Summary() : null,
                outputTurn != null ? outputTurn.getL1Summary() : null);
        String l2 = joinNonBlank(inputTurn != null ? inputTurn.getL2Detail() : null,
                outputTurn != null ? outputTurn.getL2Detail() : null);
        LinkedHashSet<Long> tagIds = new LinkedHashSet<>();
        if (inputTurn != null && inputTurn.getTagIds() != null) {
            tagIds.addAll(inputTurn.getTagIds());
        }
        if (outputTurn != null && outputTurn.getTagIds() != null) {
            tagIds.addAll(outputTurn.getTagIds());
        }
        Long sourceTurnId = outputTurn != null ? outputTurn.getId() : (inputTurn != null ? inputTurn.getId() : null);
        return new MemoryRoutingService.RoutingInput(userId, sessionId, sourceTurnId, l1, l2,
                new ArrayList<>(tagIds), chatModel);
    }

    private static String joinNonBlank(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return a + "\n" + b;
    }

    /** 大类有效词表 = base vocab（system_settings.memory.tag.vocab）∪ 用户已批准（needs_review=false）的存量 topic。
     *  使生成器把 topic 约束到大类，同概念→同 topic→路径① UNIQUE 自动合并。失败 → 仅 base vocab（不阻塞生成）。 */
    private java.util.Set<String> buildEffectiveVocab(Long userId) {
        java.util.Set<String> vocab = new java.util.LinkedHashSet<>();
        List<String> base = systemSettingService.getMemoryTagVocab();
        if (base != null) {
            vocab.addAll(base);
        }
        try {
            List<String> approved = tagMapper.findDistinctApprovedTopics(userId);
            if (approved != null) {
                vocab.addAll(approved);
            }
        } catch (Exception e) {
            log.warn("读取用户已批准 topic 失败 userId={} 仅用 base vocab: {}", userId, e.getMessage());
        }
        return vocab;
    }

    /** 写一条 turn：有生成层 → tag 归一 + L1/L2 + gen_done=true；无 → 仅 raw + gen_done=false。返回落库后的 turn。 */
    private MemoryTurn writeTurn(Long userId, Long sessionId, String direction, String rawText,
                                 MemoryGenerator.SideLayers layers, String chatModel) {
        MemoryTurn t = new MemoryTurn();
        t.setUserId(userId);
        t.setSessionId(sessionId);
        t.setDirection(direction);
        t.setRawContent(rawText);
        t.setChatModel(chatModel);
        // 审计字段显式置（异步线程无请求上下文，MetaObjectHandler 可能不填）
        t.setCreatedBy(userId);
        t.setUpdatedBy(userId);

        if (layers != null) {
            // 大类词表外映射：topic=__OTHER__ → suggestedTopic（兜底「其他」）+ needsReview=true（V77）
            String topic = layers.topic();
            boolean needsReview = false;
            if (MemoryGenerator.OTHER_TOPIC.equals(topic)) {
                topic = (layers.suggestedTopic() != null && !layers.suggestedTopic().isBlank())
                        ? layers.suggestedTopic().trim() : "其他";
                needsReview = true;
            }
            Long tagId = tagResolver.resolve(userId, layers.subject(), topic, layers.label(), needsReview);
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
        return t;
    }
}
