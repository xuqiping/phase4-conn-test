package com.superprogrammer.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.MemoryEditRequest;
import com.superprogrammer.chat.dto.MemoryProjectRow;
import com.superprogrammer.chat.dto.UserMemoryVO;
import com.superprogrammer.chat.entity.UserMemory;
import com.superprogrammer.chat.mapper.UserMemoryMapper;
import com.superprogrammer.chat.service.internal.ExtractedFact;
import com.superprogrammer.chat.service.internal.JudgeResult;
import com.superprogrammer.chat.service.internal.MemoryBlockClassifier;
import com.superprogrammer.chat.service.internal.MemoryConflictJudge;
import com.superprogrammer.chat.service.internal.MemoryQueryCache;
import com.superprogrammer.chat.service.internal.MemoryScope;
import com.superprogrammer.knowledge.service.QueryExpansionService;
import com.superprogrammer.knowledge.service.RagConfig;
import com.superprogrammer.knowledge.service.internal.RrfFusion;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import com.superprogrammer.knowledge.util.JiebaTokenizer;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 用户长期记忆服务（V27 重构，V33 加项目记忆 scope）。
 * 记忆模式 ON 时由 ChatSessionService 同步调用 processMemory：
 * 抽取 K 事实 → 每条 embed 归块 → 块非空则 LLM 冲突判定 →
 *   无冲突直插 clean / 有冲突建 PENDING（锁会话，askText 追加回复）/ 锁忙降级。
 * buildMemoryContext 注入时对 FLAGGED 行加 [⚠️冲突] 前缀 + counterpart。
 * <p>
 * V33 scope：读/写链路均带 {@link MemoryScope}。
 *   读/注入 scope = 扁平开关集（includeGlobal + 项目勾选），召回并集去重。
 *   写/冲突 scope = 单写目标（global→(true,[]) / project A→(false,[A])），冲突候选限写目标可见集。
 *   新事实 is_global=(writeTarget==null)；project 写目标→挂 user_memory_projects。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final UserMemoryMapper memoryMapper;
    private final MemoryBlockClassifier classifier;
    private final MemoryConflictJudge judge;
    private final MemoryConflictService conflictService;
    /** 记忆专用线程池（独立于 KB 索引），并行 classify embed fan-out。
     *  字段名 = bean 名 memoryTaskExecutor，Spring Boot 默认 -parameters → 按名解析（无需 @Qualifier）。 */
    private final Executor memoryTaskExecutor;
    /** 查询缓存（scope 内 distinct keys + 块成员），砍每轮 DB 往返。 */
    private final MemoryQueryCache queryCache;
    /** embedding 调用（EMBEDDING_VECTOR 召回时把 query embed 成 halfvec）。 */
    private final LlmGateway llmGateway;
    /** query 多路扩展（LLM_KEY 模式粗筛多 qHalf 提 recall；canonical embed 作 qHalfs[0]）。 */
    private final QueryExpansionService queryExpansion;
    /** 读记忆检索模式设置（LLM_FULL_CONTEXT / EMBEDDING_VECTOR / VECTOR_KEYWORD）。 */
    private final SystemSettingService systemSettingService;
    /** entities JSON 序列化（写时 List<String> → JSONB 字符串）。 */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    /** 记忆写入异常（per user，前端轮询弹一次即清）。替代旧的静默 catch。 */
    private final Map<Long, String> memoryIncidents = new java.util.concurrent.ConcurrentHashMap<>();
    /** 进行中的记忆抽取任务计数（per user）：processMemory 入 inc / finally dec。
     *  前端状态条「记忆记录中…」靠此（>0 显，ASYNC fire-and-forget 无回调，故内存计数）。 */
    private final Map<Long, java.util.concurrent.atomic.AtomicInteger> inflightMemoryTasks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** EMBEDDING_VECTOR 模式：余弦相似度 (1 - distance) 门槛 + top-K 条数。 */
    private static final double VECTOR_SIM_THRESHOLD = 0.35;
    private static final int VECTOR_TOP_K = 5;
    /** VECTOR_KEYWORD 模式：向量 + 关键词并集后注入上限。 */
    private static final int HYBRID_MAX = 8;
    /** LLM_KEY 模式：anchor 向量 top-K 余弦门槛（同 EMBEDDING_VECTOR）。 */
    private static final double ANCHOR_SIM_THRESHOLD = 0.35;
    /** LLM_KEY 模式 RRF 通道权重（向量权 > 词法权，主信号靠语义）。 */
    private static final double LLMKEY_VECTOR_WEIGHT = 1.0;
    private static final double LLMKEY_BM25_WEIGHT = 0.5;
    /** LLM_KEY 模式 RRF k 常数（同知识库默认 60，排名归一）。 */
    private static final int LLMKEY_RRF_K = 60;
    /** 注入/统计的 confidence 下限。 */
    private static final BigDecimal CONF_THRESHOLD = new BigDecimal("0.5");

    /**
     * 同步处理记忆（记忆模式 ON，回复生成后、持久化前调）。non-stream 用。
     * @param writeScope 写目标 scope（冲突候选限此可见集）
     * @param writeTargetProjectId 写目标 project（null=global）；新事实 is_global + 项目挂载据此
     * @return askText（有冲突且建了 PENDING），null=无。ChatSessionService 把它追加进同一轮回复。
     */
    public String processMemory(MemoryScope writeScope, Long writeTargetProjectId, Long sessionId,
                                String userMessage, String assistantResponse) {
        Long userId = writeScope.userId();
        inflightMemoryTasks.computeIfAbsent(userId, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
        try {
            List<ExtractedFact> facts = extractFacts(writeScope, userMessage, assistantResponse);
            log.debug("processMemory userId={} sessionId={} writeTarget={} 抽取facts={}", userId, sessionId, writeTargetProjectId, facts);
            return processFacts(writeScope, writeTargetProjectId, sessionId, userMessage, facts);
        } finally {
            java.util.concurrent.atomic.AtomicInteger a = inflightMemoryTasks.get(userId);
            if (a != null && a.decrementAndGet() <= 0) inflightMemoryTasks.remove(userId);
        }
    }

    /** 当前用户记忆处理状态（状态条轮询）：processingCount=进行中抽取数，conflictCount=待处理冲突数。 */
    public com.superprogrammer.chat.dto.MemoryStatusVO getMemoryStatus(Long userId) {
        java.util.concurrent.atomic.AtomicInteger a = inflightMemoryTasks.get(userId);
        return com.superprogrammer.chat.dto.MemoryStatusVO.builder()
                .processingCount(a == null ? 0 : a.get())
                .conflictCount(conflictService.countActive(userId))
                .build();
    }

    /** 同步 extract（含 catch）。streamChat 混合模式先调它预判，避免无冲突时白等 embed+judge。
     *  传 writeScope 以拉取写目标 scope 内既有 key 列表注入 LLM，做通用语义归一（取代手写 alias 表）。 */
    public List<ExtractedFact> extractFacts(MemoryScope writeScope, String userMessage, String assistantResponse) {
        try {
            List<String> existingKeys = queryCache.getDistinctKeys(writeScope,
                    () -> memoryMapper.findDistinctKeys(writeScope.userId(), writeScope.includeGlobal(), writeScope.safeProjectIds()));
            return judge.extract(userMessage, assistantResponse, existingKeys);
        } catch (Exception e) {
            // RB-001 根因③：此前 catch 静默返回空 list → processMemory 写 0 条 → 状态条停转但记忆面板空，
            // 真错误仅 warn 不可见。现记 incident（固定话术，不透传内部 e.getMessage()，避免情报泄露），
            // 前端轮询 /memories/incident 弹窗可见。
            log.warn("记忆抽取失败 userId={}: {}", writeScope.userId(), e.getMessage(), e);
            recordIncident(writeScope.userId(), "记忆抽取失败：服务繁忙或网络超时，本次对话未记录记忆，请稍后重试。");
            return List.of();
        }
    }

    /**
     * 快速预判：facts 任一的同 key 已存在且 value 不同 → 真·冲突（需同步 judge 拿 askText）。
     * 同 key 同 value = 重复（去重跳过，非冲突）；同 key 不存在 = 新事实。二者都走异步 insert。
     * 候选限写目标 scope 可见集（跨 scope 不冲突）。用 memoryKey 精确查（非 block）。
     */
    public boolean mayConflict(MemoryScope writeScope, List<ExtractedFact> facts) {
        if (facts == null || facts.isEmpty()) return false;
        for (ExtractedFact f : facts) {
            List<UserMemory> existing = memoryMapper.findAllClean(writeScope.userId(), writeScope.includeGlobal(), writeScope.safeProjectIds())
                    .stream()
                    .filter(m -> f.key().equals(m.getMemoryKey()))
                    .toList();
            boolean conflict = existing.stream().anyMatch(m -> !f.value().equals(m.getMemoryValue()));
            if (conflict) return true;
        }
        return false;
    }

    /** 处理已 extract 的 facts（归块+去重+冲突判定+入库）。返回 askText。同步或异步调用均可。
     *  三段式（批 judge 优化）：①预去重+classify 全量 ②按块聚合成员、分流（新块直插/重复跳过/待判定入桶）③每块一次 batch judge。 */
    public String processFacts(MemoryScope writeScope, Long writeTargetProjectId, Long sessionId,
                               String userMessage, List<ExtractedFact> facts) {
        if (facts == null || facts.isEmpty()) return null;
        Long userId = writeScope.userId();
        log.debug("processFacts userId={} sessionId={} writeTarget={} facts={}", userId, sessionId, writeTargetProjectId, facts);
        String askText = null;

        // ① 预去重（同 key 同 value clean 已存→跳过，省 embed）+ classify（embed 归块）。并行 embed（互相独立）。
        //    注：processMemory 已在 servlet/boundedElastic 上（非 reactor-nio），join() 安全。
        List<CompletableFuture<FactClassed>> futures = new ArrayList<>();
        for (ExtractedFact f : facts) {
            futures.add(CompletableFuture.supplyAsync(() -> classifyFact(writeScope, writeTargetProjectId, f), memoryTaskExecutor));
        }
        List<FactClassed> classed = new ArrayList<>();
        for (CompletableFuture<FactClassed> fut : futures) {
            try {
                FactClassed fc = fut.join();
                if (fc != null) classed.add(fc);
            } catch (Exception e) {
                log.warn("processFacts classify join 失败 userId={}: {}", userId, e.getMessage(), e);
            }
        }

        // ② 查块成员（每块一次，缓存→一致快照）+ 跨块同 key 记忆（home-scoped）+ 分流
        // V35 修「记忆直接被修改了」：同 key 既有记忆可能在【别的块】（块成员查询是 block-scoped，看不到），
        //   旧逻辑「块成员空→immediateInserts→applyClean」会用 findSameKeyClean（跨块）找到别块同类记忆当「细化」
        //   原地 UPDATE 覆盖，绕过冲突判定 → 用户感知「记忆被直接改了，没问我」。
        //   修法：分流以「块成员 ∪ 跨块同 key 记忆」为准，二者皆空才真·新块直插；否则进 judge 让 LLM 判冲突。
        Map<String, List<UserMemory>> membersByBlock = new HashMap<>();
        Map<String, List<UserMemory>> sameKeyByFactKey = new HashMap<>();   // 跨块同 key clean 记忆（home-scoped，冲突候选补集）
        List<FactClassed> immediateInserts = new ArrayList<>();             // 真·新（块内无成员 且 跨块无同 key），无冲突可能
        Map<String, List<FactClassed>> judgeBuckets = new java.util.LinkedHashMap<>();  // 待判定，按块聚合
        for (FactClassed fc : classed) {
            List<UserMemory> members = membersByBlock.computeIfAbsent(fc.br.blockLabel(),
                    bl -> queryCache.getCleanByBlock(writeScope, bl,
                            () -> memoryMapper.findCleanByBlock(userId, bl, writeScope.includeGlobal(), writeScope.safeProjectIds())));
            List<UserMemory> sameKey = sameKeyByFactKey.computeIfAbsent(fc.f.key(),
                    k -> memoryMapper.findCleanByHomeKey(userId, k, writeTargetProjectId));
            final String fk = fc.f.key(), fv = fc.f.value();
            boolean dup = members.stream().anyMatch(m -> fk.equals(m.getMemoryKey()) && fv.equals(m.getMemoryValue()))
                    || sameKey.stream().anyMatch(m -> fk.equals(m.getMemoryKey()) && fv.equals(m.getMemoryValue()));
            if (dup) {
                log.debug("processFacts 重复跳过 key={} value={}", fk, fv);
                continue;
            }
            if (members.isEmpty() && sameKey.isEmpty()) {
                immediateInserts.add(fc);
                continue;
            }
            judgeBuckets.computeIfAbsent(fc.br.blockLabel(), k -> new ArrayList<>()).add(fc);
        }

        // ③ 每块一次 batch judge（取代逐条 judge：N 次判定→#blocks 次）
        for (var entry : judgeBuckets.entrySet()) {
            String block = entry.getKey();
            List<FactClassed> bucket = entry.getValue();
            List<UserMemory> members = membersByBlock.getOrDefault(block, java.util.List.of());
            // comparison set = 块成员 ∪ 桶内各 fact 跨块同 key 记忆：让 LLM 看到别块同类记忆，否则跨块同 key 漏判冲突
            java.util.LinkedHashMap<Long, UserMemory> cmpMap = new java.util.LinkedHashMap<>();
            for (UserMemory m : members) cmpMap.put(m.getId(), m);
            for (FactClassed fc : bucket)
                for (UserMemory m : sameKeyByFactKey.getOrDefault(fc.f.key(), java.util.List.of())) cmpMap.put(m.getId(), m);
            List<UserMemory> cmpSet = new ArrayList<>(cmpMap.values());
            log.debug("processFacts batch judge block={} facts={} cmpSet={}", block, bucket.size(), cmpSet.size());
            List<ExtractedFact> bucketFacts = bucket.stream().map(FactClassed::f).collect(Collectors.toList());
            List<JudgeResult> jrs;
            try {
                jrs = judge.judge(bucketFacts, cmpSet, userMessage);
            } catch (Exception e) {
                log.warn("processFacts batch judge 失败 block={}: {} → 全部 fail-safe applyClean", block, e.getMessage());
                bucket.forEach(fc -> applyClean(writeScope, writeTargetProjectId, fc.f, fc.br));
                continue;
            }
            for (int i = 0; i < bucket.size(); i++) {
                FactClassed fc = bucket.get(i);
                ExtractedFact f = fc.f;
                JudgeResult r = (jrs == null || i >= jrs.size()) ? null : jrs.get(i);
                try {
                    if (r == null || !r.conflict()) {
                        applyClean(writeScope, writeTargetProjectId, f, fc.br);          // 不矛盾（同 key 细化则 UPDATE）
                        continue;
                    }
                    // 冲突：会话锁空→PENDING（首轮，带 askText）；锁忙→直建 FLAGGED（不丢事实，面板可见/可批 resolve，不占锁不阻塞后续）
                    // conflictingIds 空 → 回退该 fact 的跨块同 key 记忆（最可能冲突对象），再兜底块成员
                    List<Long> ids;
                    if (r.conflictingIds() != null && !r.conflictingIds().isEmpty()) {
                        ids = r.conflictingIds();
                    } else {
                        ids = sameKeyByFactKey.getOrDefault(f.key(), java.util.List.of()).stream()
                                .map(UserMemory::getId).collect(Collectors.toList());
                        if (ids.isEmpty()) ids = members.stream().map(UserMemory::getId).collect(Collectors.toList());
                    }
                    var snap = new MemoryConflictService.ExtractedFactSnapshot(
                            f.category(), f.key(), f.value(), f.confidence().toPlainString(), fc.br.halfvec());
                    var pending = conflictService.getActivePendingOrExpire(sessionId, userId);
                    if (pending == null) {
                        conflictService.createPending(userId, sessionId, fc.br.blockLabel(), snap, ids, r.askText());
                        if (askText == null) askText = r.askText();   // 多事实只取首个 ask
                    } else {
                        log.info("会话 {} 记忆锁忙，事实 {} 直建 FLAGGED", sessionId, f.key());
                        conflictService.createFlagged(userId, sessionId, fc.br.blockLabel(), snap, ids, writeTargetProjectId);
                    }
                } catch (Exception e) {
                    log.warn("processFacts 单事实处理失败 userId={} key={}: {}", userId, f.key(), e.getMessage(), e);
                }
            }
        }

        // 新块事实最后写（不污染上面 members 快照；同 key 跨块也走 applyClean 兜底 UPDATE）
        for (FactClassed fc : immediateInserts) applyClean(writeScope, writeTargetProjectId, fc.f, fc.br);
        return askText;
    }

    /** 单 fact 预去重 + classify（embed 归块）。供并行 supplyAsync 调用。
     *  返回 null = 命中预去重 / 异常（调用方跳过）。候选限写目标 scope 可见集。异常隔离：单 fact 失败不影响其他。 */
    private FactClassed classifyFact(MemoryScope writeScope, Long writeTargetProjectId, ExtractedFact f) {
        try {
            List<UserMemory> sameKey = findSameKeyClean(writeScope.userId(), f.key(), writeTargetProjectId);
            if (sameKey.stream().anyMatch(m -> f.value().equals(m.getMemoryValue()))) {
                log.debug("processFacts 预去重跳过 key={} value={}", f.key(), f.value());
                return null;
            }
            String factText = f.key() + ":" + f.value();
            MemoryBlockClassifier.BlockResult br = classifier.classify(writeScope, factText, f.block());
            return new FactClassed(f, br);
        } catch (Exception e) {
            log.warn("processFacts classify 失败 userId={} key={}: {}", writeScope.userId(), f.key(), e.getMessage(), e);
            return null;
        }
    }

    /** fact + 其 classify 结果（blockLabel + halfvec），批 judge 流水线内部载体。 */
    private record FactClassed(ExtractedFact f, MemoryBlockClassifier.BlockResult br) {}

    /** 写一条 clean 记忆。同 key 既有 clean 行（refinement/补充，值不同）→ UPDATE 覆盖；否则 INSERT。
     *  INSERT 撞唯一约束 → 兜底 UPDATE；再失败 → 记 incident + ERROR（绝不静默吞，丢值要让前端知道）。
     *  V33：新行 is_global=(writeTargetProjectId==null)；project 写目标→挂 user_memory_projects。refinement UPDATE 不改 scope。 */
    private void applyClean(MemoryScope writeScope, Long writeTargetProjectId, ExtractedFact f, MemoryBlockClassifier.BlockResult br) {
        Long userId = writeScope.userId();
        String entitiesJson = entitiesJson(f.entities());
        // V38：anchor（block+key+key_zh+entities）与 value embed 同批落（不新增独立 LLM 调用逻辑分支）
        AnchorEmbedding anchor = embedAnchor(br.blockLabel(), f.keyZh(), f.key(), entitiesJson);
        List<UserMemory> sames = findSameKeyClean(userId, f.key(), writeTargetProjectId);
        UserMemory existing = sames.isEmpty() ? null : sames.get(0);
        if (existing != null) {
            // 同 key 既有 clean（同值已被预去重拦，此处必为不同值=细化）→ 原地覆盖（scope 不变）
            int n = memoryMapper.updateCleanMemory(existing.getId(), f.value(), f.confidence(), br.blockLabel(), br.halfvec(), entitiesJson, f.keyZh(), anchor.halfvec(), anchor.tokens());
            if (n > 0) {
                queryCache.evictUser(userId);
                log.debug("applyClean 细化更新 key={} id={} value={}", f.key(), existing.getId(), f.value());
                return;
            }
        }
        boolean isGlobal = writeTargetProjectId == null;
        UserMemory m = newMemory(userId, f, br, isGlobal, writeTargetProjectId);
        m.setEntities(entitiesJson);
        m.setMemoryKeyZh(f.keyZh());
        try {
            memoryMapper.insertMemory(m, br.halfvec(), anchor.halfvec(), anchor.tokens());
            if (!isGlobal) {
                memoryMapper.insertMemoryProjects(m.getId(), List.of(writeTargetProjectId));
            }
            queryCache.evictUser(userId);
        } catch (Exception e) {
            // 插入撞唯一（并发/漏判 refinement）→ 兜底 UPDATE
            List<UserMemory> againSames = findSameKeyClean(userId, f.key(), writeTargetProjectId);
            if (!againSames.isEmpty()) {
                UserMemory again = againSames.get(0);
                memoryMapper.updateCleanMemory(again.getId(), f.value(), f.confidence(), br.blockLabel(), br.halfvec(), entitiesJson, f.keyZh(), anchor.halfvec(), anchor.tokens());
                queryCache.evictUser(userId);
                log.info("applyClean 插入撞唯一兜底更新 key={} id={}", f.key(), again.getId());
                return;
            }
            // 真异常：不吞，记 incident 供前端轮询弹出
            log.error("applyClean 失败 userId={} key={}: {}", userId, f.key(), e.getMessage(), e);
            memoryIncidents.put(userId, "记忆写入失败：" + f.key() + "（" + e.getMessage() + "）");
        }
    }

    /** entities List → JSON 字符串（落 entities JSONB 列）；空/null → null（不参与关键词召回）。 */
    private String entitiesJson(List<String> entities) {
        if (entities == null || entities.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(entities);
        } catch (Exception e) {
            log.warn("entities 序列化失败 {}: {}", entities, e.getMessage());
            return null;
        }
    }

    // ============================ V38 anchor 召回锚点（LLM_KEY 粗筛）============================

    /** anchor 向量（halfvec）+ 词法 token（jieba 空格串）载体。null = 该维缺失（回填/重试补）。 */
    record AnchorEmbedding(String halfvec, String tokens) {}

    /** anchor 文本 = block_label + memory_key_zh + memory_key + entities(展开)，null 安全（空段跳过）。
     *  标签+词袋语义，比 value 向量稳、比 key_zh 丰富 → embed 靠近召回意图（如"家人"）。 */
    String buildAnchorText(UserMemory m) {
        return buildAnchorText(m.getBlockLabel(), m.getMemoryKeyZh(), m.getMemoryKey(), m.getEntities());
    }

    /** 字段直传重载（UPDATE 路径用新字段而非既有实体，避构造临时实体）。 */
    String buildAnchorText(String blockLabel, String keyZh, String key, String entitiesJson) {
        List<String> parts = new ArrayList<>();
        if (blockLabel != null && !blockLabel.isBlank()) parts.add(blockLabel);
        if (keyZh != null && !keyZh.isBlank()) parts.add(keyZh);
        if (key != null && !key.isBlank()) parts.add(key);
        String ent = expandEntities(entitiesJson);
        if (ent != null && !ent.isBlank()) parts.add(ent);
        return String.join(" ", parts);
    }

    /** entities JSONB 串 ["a","b"] → "a b"（展开进 anchor 文本）；空/null/解析失败 → null。 */
    String expandEntities(String entitiesJson) {
        if (entitiesJson == null || entitiesJson.isBlank()) return null;
        try {
            List<String> list = objectMapper.readValue(entitiesJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            if (list == null || list.isEmpty()) return null;
            String joined = list.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(" "));
            return joined.isBlank() ? null : joined;
        } catch (Exception e) {
            log.warn("expandEntities 解析失败 {}: {}", entitiesJson, e.getMessage());
            return null;
        }
    }

    /** embed anchor 文本 → (halfvec, tokens)。embed 失败 → halfvec=null（tokens 仍落，向量待回填），不抛（同 value embed 韧性）。
     *  与 value embed 同模型（MEMORY_EMBED_MODEL）第二次调用；anchor 不替代 value 向量。 */
    AnchorEmbedding embedAnchor(UserMemory m) {
        return embedAnchor(m.getBlockLabel(), m.getMemoryKeyZh(), m.getMemoryKey(), m.getEntities());
    }

    AnchorEmbedding embedAnchor(String blockLabel, String keyZh, String key, String entitiesJson) {
        String text = buildAnchorText(blockLabel, keyZh, key, entitiesJson);
        if (text.isBlank()) return new AnchorEmbedding(null, null);
        String tokens = JiebaTokenizer.tokenize(text);
        try {
            float[] vec = llmGateway.embed(text, RagConfig.MEMORY_EMBED_MODEL);
            return new AnchorEmbedding(HalfVecUtil.toHalfVec(vec), tokens);
        } catch (Exception e) {
            log.warn("anchor embed 失败 text={}: {}", text, e.getMessage());
            return new AnchorEmbedding(null, tokens);
        }
    }

    /** V34：同 user 同 home 同 key 的 clean 行（home-aware dedup，与唯一索引对齐）；无则空。
     *  homeId 即写目标 project id（null=global home）。取代旧 scope-filtered 查询——
     *  旧版按读可见性 scope 过滤，写目标看不到的老行会漏判 → INSERT 撞唯一约束报错。 */
    private List<UserMemory> findSameKeyClean(Long userId, String key, Long homeId) {
        return memoryMapper.findCleanByHomeKey(userId, key, homeId);
    }

    private UserMemory newMemory(Long userId, ExtractedFact f, MemoryBlockClassifier.BlockResult br, boolean isGlobal, Long homeProjectId) {
        UserMemory m = new UserMemory();
        m.setUserId(userId);
        m.setCategory(f.category());
        m.setMemoryKey(f.key());
        m.setMemoryKeyZh(f.keyZh());
        m.setMemoryValue(f.value());
        m.setSource("INFERRED");
        m.setConfidence(f.confidence());
        m.setBlockLabel(br.blockLabel());
        m.setIsGlobal(isGlobal);
        m.setHomeProjectId(homeProjectId);
        return m;
    }

    /** 取并清除当前用户的记忆写入异常（前端轮询弹一次即清）。null=无。 */
    public String getAndClearIncident(Long userId) {
        return memoryIncidents.remove(userId);
    }

    /**
     * 记录一条记忆处理异常，供前端轮询弹窗（与 applyClean 写库失败同通道）。
     * <p>用于异步任务被线程池拒绝（AbortPolicy）或抽取阶段失败时可见化——绝不静默吞。
     */
    public void recordIncident(Long userId, String msg) {
        if (userId == null) return;
        memoryIncidents.put(userId, msg);
    }

    // ============================ 注入（下游 LLM system msg）============================

    /**
     * 记忆召回入口：按系统设置 retrievalMode 分流。V33 带读 scope（扁平开关集）。
     *  LLM_FULL_CONTEXT（默认）：scope 内全量 confidence≥0.5 记忆灌入（向后兼容：global scope=今天行为）。
     *  EMBEDDING_VECTOR：query embed → pgvector top-K 余弦检索（限 scope），仅注入强相关记忆；无命中 → 返回 null。
     *  VECTOR_KEYWORD：向量 top-K ∪ 关键词(实体列)召回（限 scope）；并集 0 命中 → LLM-key 兜底。
     */
    public String buildMemoryContext(MemoryScope readScope, String query) {
        return buildMemoryContext(readScope, query, null);
    }

    /** 带召回过程收集器的重载（trace!=null 仅预览路径，收集粗筛候选/选中/通道计数；真实注入传 null 零开销）。 */
    public String buildMemoryContext(MemoryScope readScope, String query, RecallTrace trace) {
        String mode = systemSettingService.getMemoryRetrievalMode();
        String keyLang = systemSettingService.getMemoryKeyLanguage();
        int threshold = systemSettingService.getMemoryFullContextThreshold();
        String context;
        if ("EMBEDDING_VECTOR".equals(mode)) {
            context = buildVectorContext(readScope, query);
        } else if ("VECTOR_KEYWORD".equals(mode)) {
            context = buildHybridContext(readScope, query);
        } else if ("LLM_KEY".equals(mode)) {
            context = buildLlmKeyContext(readScope, query, trace);
        } else {
            context = buildFullContext(readScope, query);
        }
        // 可观测性：三个记忆检索设置（mode/keyLang/threshold）生效与否全靠这条日志。
        if (context == null || context.isEmpty()) {
            log.info("记忆注入 userId={} scope={} mode={} keyLang={} threshold={} query.len={} → 不注入(无命中/LLM判无关)",
                    readScope.userId(), MemoryQueryCache.scopeSig(readScope), mode, keyLang, threshold, query == null ? 0 : query.length());
        } else {
            String sample = context.contains("\n") ? context.substring(0, context.indexOf('\n')) : context;
            if (sample.length() > 80) sample = sample.substring(0, 80);
            log.debug("记忆注入 userId={} scope={} mode={} keyLang={} threshold={} query.len={} → 注入{}字符 首条[{}]",
                    readScope.userId(), MemoryQueryCache.scopeSig(readScope), mode, keyLang, threshold, query == null ? 0 : query.length(), context.length(), sample);
        }
        return context;
    }

    /**
     * 记忆注入预览（调试用，前端面板展示）：返回三个检索设置的当前值 + scope 内记忆总数 +
     * 是否触发超阈值两阶段 + 实际注入 LLM 的上下文文本。供用户直观验证设置是否生效。
     */
    public com.superprogrammer.chat.dto.MemoryContextPreviewVO previewContext(MemoryScope readScope, String query) {
        String mode = systemSettingService.getMemoryRetrievalMode();
        String keyLang = systemSettingService.getMemoryKeyLanguage();
        int threshold = systemSettingService.getMemoryFullContextThreshold();
        Long total = memoryMapper.countByScope(readScope.userId(), CONF_THRESHOLD,
                readScope.includeGlobal(), readScope.safeProjectIds());
        // 预览专用召回过程收集器（仅本路径构造，真实注入路径不传 → 零开销）
        RecallTrace trace = new RecallTrace();
        String context = buildMemoryContext(readScope, query, trace);
        boolean twoStage = "LLM_FULL_CONTEXT".equals(mode) && threshold > 0
                && total != null && total > threshold
                && query != null && !query.isBlank();
        return com.superprogrammer.chat.dto.MemoryContextPreviewVO.builder()
                .mode(mode).keyLanguage(keyLang).threshold(threshold)
                .totalMemories(total).twoStage(twoStage).context(context)
                .candidates(toCandidateVOs(trace))
                .selectedKeys(trace.selectedKeys)
                .channels(com.superprogrammer.chat.dto.MemoryContextPreviewVO.ChannelHitVO.builder()
                        .vector(trace.vectorCount).bm25(trace.bm25Count).build())
                .build();
    }

    /** trace.candidates → CandidateVO 列表（key_zh + value 预览 + block + scope + 命中通道）。空 → null。 */
    private List<com.superprogrammer.chat.dto.MemoryContextPreviewVO.CandidateVO> toCandidateVOs(RecallTrace trace) {
        if (trace == null || trace.candidates == null || trace.candidates.isEmpty()) return null;
        List<com.superprogrammer.chat.dto.MemoryContextPreviewVO.CandidateVO> out = new ArrayList<>();
        for (UserMemory m : trace.candidates) {
            String val = m.getMemoryValue() == null ? "" : m.getMemoryValue();
            if (val.length() > 60) val = val.substring(0, 60);
            out.add(com.superprogrammer.chat.dto.MemoryContextPreviewVO.CandidateVO.builder()
                    .memoryKeyZh(m.getMemoryKeyZh())
                    .memoryKey(m.getMemoryKey())
                    .valuePreview(val)
                    .blockLabel(m.getBlockLabel() == null ? "" : m.getBlockLabel())
                    .scope(Boolean.TRUE.equals(m.getIsGlobal()) ? "global" : "project")
                    .channel(trace.channelById.get(m.getId()))
                    .build());
        }
        return out;
    }

    /** 预览路径召回过程收集器（buildMemoryContext(scope,query,trace) 仅预览构造，真实注入传 null）。
     *  记录粗筛候选 + 通道归属 + 选中 key + 通道命中数，供前端「召回过程」折叠区展示。 */
    public static final class RecallTrace {
        /** 粗筛 top-N 候选（RRF 降序）。 */
        List<UserMemory> candidates;
        /** id → 命中通道（vector/bm25/both）。 */
        final Map<Long, String> channelById = new java.util.LinkedHashMap<>();
        /** LLM 精排选中 memory_key 列表（rerank=false 时留 null）。 */
        List<String> selectedKeys;
        int vectorCount;
        int bm25Count;
        void populateChannels(int vec, int bm25) { this.vectorCount = vec; this.bm25Count = bm25; }
    }

    /** 向量 top-K 召回（EMBEDDING_VECTOR 模式，限 scope）。无命中/异常 → null。 */
    private String buildVectorContext(MemoryScope readScope, String query) {
        if (query == null || query.isBlank()) return null;
        try {
            float[] vec = llmGateway.embed(query, RagConfig.MEMORY_EMBED_MODEL);
            String halfvec = HalfVecUtil.toHalfVec(vec);
            List<UserMemory> hits = memoryMapper.findTopKByVector(readScope.userId(), halfvec, VECTOR_SIM_THRESHOLD, VECTOR_TOP_K,
                    readScope.includeGlobal(), readScope.safeProjectIds());
            if (hits == null || hits.isEmpty()) return null;
            return formatMemories(readScope.userId(), hits);
        } catch (Exception e) {
            log.warn("向量记忆检索失败 userId={}: {} → 不注入", readScope.userId(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 混合召回（VECTOR_KEYWORD 模式，限 scope）：向量 top-K ∪ 关键词(实体列) 召回 → 去重截断注入；
     * 并集 0 命中 → LLM-key 兜底（把 scope 内全部 clean 记忆 key=>value 灌 LLM 挑相关）。
     * 任一子链路异常不致命：向量挂→仅关键词；关键词挂→仅向量；都空→兜底；兜底也空→不注入。
     */
    private String buildHybridContext(MemoryScope readScope, String query) {
        if (query == null || query.isBlank()) return null;
        Long userId = readScope.userId();
        List<UserMemory> hits = new ArrayList<>();
        // 1. 向量 top-K
        try {
            float[] vec = llmGateway.embed(query, RagConfig.MEMORY_EMBED_MODEL);
            String halfvec = HalfVecUtil.toHalfVec(vec);
            List<UserMemory> v = memoryMapper.findTopKByVector(userId, halfvec, VECTOR_SIM_THRESHOLD, VECTOR_TOP_K,
                    readScope.includeGlobal(), readScope.safeProjectIds());
            if (v != null) hits.addAll(v);
        } catch (Exception e) {
            log.warn("hybrid 向量检索失败 userId={}: {}", userId, e.getMessage());
        }
        // 2. 关键词（实体列）召回
        List<String> kws = tokenize(query);
        if (!kws.isEmpty()) {
            try {
                List<UserMemory> k = memoryMapper.findByKeyword(userId, kws, readScope.includeGlobal(), readScope.safeProjectIds());
                if (k != null) hits.addAll(applyKeywordPerBlockThreshold(k, kws));
            } catch (Exception e) {
                log.warn("hybrid 关键词检索失败 userId={}: {}", userId, e.getMessage());
            }
        }
        // 3. 去重 + 截断
        List<UserMemory> merged = hits.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .limit(HYBRID_MAX)
                .collect(Collectors.toList());
        if (!merged.isEmpty()) return formatMemories(userId, merged);
        // 4. 0 命中 → LLM-key 兜底（漏召救场，非常驻路径）
        log.info("hybrid 0 命中，触发 LLM-key 兜底 userId={} query.len={}", userId, query.length());
        return llmFallbackContext(readScope, query);
    }

    /** 关键词召回 per-block_label 阈值筛选（V34）：取代旧全局 LIMIT 10。
     *  按 block_label 分组（null 视作 ""），各组各自阈值 N（设置 rag.memory.keyword-per-block-threshold，默认10，0=禁用）：
     *  组内 ≤ N → 全留；组内 > N → 命中 entities/memory_key/memory_key_zh(高优) 的行不卡阈值全留，
     *  低优(memory_value/block_label 命中)补到 N。N≤0(禁用) → 原样返回，由下游 HYBRID_MAX 截断。 */
    private List<UserMemory> applyKeywordPerBlockThreshold(List<UserMemory> kwHits, List<String> tokens) {
        if (kwHits == null || kwHits.isEmpty()) return List.of();
        int n = systemSettingService.getMemoryKeywordPerBlockThreshold();
        if (n <= 0) return kwHits;
        Map<String, List<UserMemory>> byBlock = new java.util.LinkedHashMap<>();
        for (UserMemory m : kwHits) {
            String blk = m.getBlockLabel() == null ? "" : m.getBlockLabel();
            byBlock.computeIfAbsent(blk, k -> new ArrayList<>()).add(m);
        }
        List<UserMemory> out = new ArrayList<>();
        for (List<UserMemory> group : byBlock.values()) {
            if (group.size() <= n) { out.addAll(group); continue; }
            List<UserMemory> high = new ArrayList<>();
            List<UserMemory> low = new ArrayList<>();
            for (UserMemory m : group) {
                if (isHighPriorityHit(m, tokens)) high.add(m); else low.add(m);
            }
            out.addAll(high); // 高优不卡阈值（哪怕组内 15 个高优全留）
            if (high.size() < n) {
                int fill = Math.min(n - high.size(), low.size());
                out.addAll(low.subList(0, fill)); // 低优补到阈值 N
            }
        }
        return out;
    }

    /** 高优命中判定：该行被 tokens 命中在 entities(JSON 原文) / memory_key / memory_key_zh 任一。
     *  低优 = 仅 memory_value / block_label 命中。大小写不敏感子串匹配（CJK 原样）。 */
    private static boolean isHighPriorityHit(UserMemory m, List<String> tokens) {
        String entities = m.getEntities() == null ? "" : m.getEntities();
        String key = m.getMemoryKey() == null ? "" : m.getMemoryKey();
        String keyZh = m.getMemoryKeyZh() == null ? "" : m.getMemoryKeyZh();
        for (String t : tokens) {
            if (containsIgnoreCase(entities, t) || containsIgnoreCase(key, t) || containsIgnoreCase(keyZh, t)) return true;
        }
        return false;
    }

    private static boolean containsIgnoreCase(String hay, String needle) {
        if (hay == null || needle == null || needle.isEmpty()) return false;
        return hay.toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT));
    }

    /** LLM-key 兜底：拉 scope 内全部 clean 记忆，filterRelevantKeys 挑相关 key 装载注入。
     *  即"先加载标签(key)→LLM 判→再加载内容"的正确落地，仅 0 命中触发。失败/无关 → null。 */
    private String llmFallbackContext(MemoryScope readScope, String query) {
        List<UserMemory> all = memoryMapper.findAllClean(readScope.userId(), readScope.includeGlobal(), readScope.safeProjectIds());
        if (all.isEmpty()) return null;
        return filterRelevantKeys(readScope, query, all);
    }

    /** 两阶段召回公共筛（V38 三维 key × key_zh × block_label，一次 LLM）：三路径共用
     * （fullContext 超阈值 / hybrid 0命中兜底 / LLM_KEY 精排）。
     * <ol>
     *   <li>distinct memory_key（带 key_zh/value）+ distinct block_label（null→""）→ judge.selectRelevantKeysBlocks
     *       一次 LLM 同时选 keys / keys_zh / blocks（召回优先）；</li>
     *   <li>三维 AND 交集装配：key∈keys 且 key_zh∈keys_zh 且 block∈blocks。</li>
     * </ol>
     * 剔重在 Java 端（Stream），不新增 DB 查询。容错：单次 LLM 失败/解析失败 → null（不注入，宁缺毋滥）。
     * key_zh 空白行（老数据无中文标签）通配通过该维，不被误杀。注入只留 key+value（噪声由 formatLine 控）。
     * key 匹配始终用英文 memory_key；key 语言设置只影响 formatLine 展示。 */
    private String filterRelevantKeys(MemoryScope readScope, String query, List<UserMemory> allMemories) {
        List<UserMemory> sel = selectRelevantMemories(readScope, query, allMemories);
        return sel == null ? null : formatMemories(readScope.userId(), sel);
    }

    /** 三维精排核心（key ∩ key_zh ∩ block），返回选中记忆（未格式化）。null = 输入空 / LLM 失败 / 三维交集空 / 异常。
     *  供 filterRelevantKeys（格式化注入）与 LLM_KEY 缓存 loader（取选中 key 列表）共用。 */
    private List<UserMemory> selectRelevantMemories(MemoryScope readScope, String query, List<UserMemory> allMemories) {
        if (allMemories == null || allMemories.isEmpty()) return null;
        Long userId = readScope.userId();
        try {
            // distinct memory_key（同 key 多行去重，省 token；每行带 key_zh/value 供 LLM 展示+校验）
            List<UserMemory> distinctByKey = new ArrayList<>(allMemories.stream()
                    .collect(Collectors.toMap(UserMemory::getMemoryKey, m -> m, (a, b) -> a, java.util.LinkedHashMap::new))
                    .values());
            List<String> distinctBlocks = allMemories.stream()
                    .map(m -> m.getBlockLabel() == null ? "" : m.getBlockLabel())
                    .distinct().collect(Collectors.toList());

            // 三维合并一次 LLM（keys + keys_zh + blocks）；失败/解析失败 → null（不注入，fail-safe）
            MemoryConflictJudge.RelevantDims dims = judge.selectRelevantKeysBlocks(query, distinctByKey, distinctBlocks);
            if (dims == null) return null;

            final Set<String> fk = dims.keys();
            final Set<String> fkz = dims.keysZh();
            final Set<String> fb = dims.blocks();
            // 三维 AND 交集；key_zh 空白行（老数据无标签）通配通过该维，避免被误杀
            List<UserMemory> sel = allMemories.stream()
                    .filter(m -> fk.contains(m.getMemoryKey())
                              && keyZhPassesDim(m.getMemoryKeyZh(), fkz)
                              && fb.contains(m.getBlockLabel() == null ? "" : m.getBlockLabel()))
                    .collect(Collectors.toList());
            return sel.isEmpty() ? null : sel;
        } catch (Exception e) {
            log.warn("selectRelevantMemories 三维筛失败 userId={}: {} → 不注入", userId, e.getMessage(), e);
            return null;
        }
    }

    /** key_zh 维判定：行 key_zh 空白（老数据无中文标签）→ 通配通过；否则需 ∈ 选中集合 keysZh。 */
    private static boolean keyZhPassesDim(String keyZh, Set<String> keysZh) {
        return keyZh == null || keyZh.isBlank() || (keysZh != null && keysZh.contains(keyZh));
    }

    /**
     * query 分词（VECTOR_KEYWORD 关键词召回用）：CJK 连续段切 2-gram，字母数字段整段保留。
     * 噪声 gram（如"去玩"）天然自滤——它们不是任何实体的子串，ILIKE 撞不上 entities 列。
     * 去重 + 截断 keyword-max 设置（默认 8，0=不限），避免 SQL OR 列表过长。
     */
    private List<String> tokenize(String query) {
        if (query == null || query.isBlank()) return List.of();
        int max = systemSettingService.getKeywordMax();
        long cap = max <= 0 ? Long.MAX_VALUE : max;
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]{2,}|[A-Za-z0-9]{2,}").matcher(query);
        while (m.find()) {
            String seg = m.group();
            if (isAscii(seg)) {
                out.add(seg.toLowerCase());
            } else {
                for (int i = 0; i + 2 <= seg.length(); i++) out.add(seg.substring(i, i + 2));
            }
        }
        return out.stream().distinct().limit(cap).collect(Collectors.toList());
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    /**
     * LLM_KEY 召回（V38 anchor 语义两阶段，限 scope）：百万 key 场景治本路径。
     * <ol>
     *   <li>query 多路扩展（{@code QueryExpansionService.expand} → canonical + qHalfs[]，多 qHalf 提 recall）；扩展挂 → 单 qHalf（canonical embed 兜底）</li>
     *   <li>粗筛：每 qHalf 跑 {@code findTopKByAnchor}（anchor 向量 top-K）+ {@code findAnchorBm25}（jieba BM25）→ RRF 融合（向量权 > 词法权）→ top-N</li>
     *   <li>rerank=true：{@code selectRelevantMemories} 双维度精排（key + block_label 交集）；false：直接注 top-N</li>
     *   <li>选中 key 列表经 {@code MemoryQueryCache.getRerankKeys} 缓存（TTL 60s，同 query 命中跳精排 LLM）</li>
     * </ol>
     * 异常降级（宁少勿滥，不回退全量，同 EMBEDDING_VECTOR）：向量挂→仅 BM25；BM25 挂→仅向量；都空→null。 */
    private String buildLlmKeyContext(MemoryScope readScope, String query, RecallTrace trace) {
        if (query == null || query.isBlank()) return null;
        Long userId = readScope.userId();
        boolean includeGlobal = readScope.includeGlobal();
        List<Long> projectIds = readScope.safeProjectIds();
        int topN = systemSettingService.getLlmKeyCoarseTopN();
        boolean rerank = systemSettingService.getLlmKeyRerank();

        // ① query 多路扩展（canonical embed 作 qHalfs[0]）；扩展挂 → 单 qHalf 兜底
        List<String> qHalfs = null;
        try {
            QueryExpansionService.ExpandedQuery eq = queryExpansion.expand(query, RagConfig.MEMORY_EMBED_MODEL);
            if (eq != null && eq.qHalfs() != null && !eq.qHalfs().isEmpty()) qHalfs = eq.qHalfs();
        } catch (Exception e) {
            log.warn("LLM_KEY query 扩展失败 userId={}: {} → 单 qHalf 兜底", userId, e.getMessage());
        }
        if (qHalfs == null) {
            try {
                float[] vec = llmGateway.embed(query, RagConfig.MEMORY_EMBED_MODEL);
                qHalfs = new ArrayList<>(List.of(HalfVecUtil.toHalfVec(vec)));
            } catch (Exception e) {
                log.warn("LLM_KEY canonical embed 失败 userId={}: {}", userId, e.getMessage());
                qHalfs = List.of();
            }
        }

        // ② 粗筛：anchor 向量（每 qHalf 一条 ranked list）+ BM25，收集 id→UserMemory 去重表 + 通道归属
        Map<Long, UserMemory> byId = new java.util.LinkedHashMap<>();
        java.util.Set<Long> vecIds = new java.util.LinkedHashSet<>();
        java.util.Set<Long> bm25Ids = new java.util.LinkedHashSet<>();
        List<RrfFusion.WeightedList<Long>> channelLists = new ArrayList<>();
        int vecChannels = 0;
        for (String qh : qHalfs) {
            try {
                List<UserMemory> v = memoryMapper.findTopKByAnchor(userId, qh, ANCHOR_SIM_THRESHOLD, topN, includeGlobal, projectIds);
                if (v != null && !v.isEmpty()) {
                    List<Long> order = new ArrayList<>();
                    for (UserMemory m : v) {
                        if (m != null && m.getId() != null) { byId.putIfAbsent(m.getId(), m); vecIds.add(m.getId()); order.add(m.getId()); }
                    }
                    if (!order.isEmpty()) { channelLists.add(new RrfFusion.WeightedList<>(order, LLMKEY_VECTOR_WEIGHT)); vecChannels++; }
                }
            } catch (Exception e) {
                log.warn("LLM_KEY anchor 向量检索失败 userId={}: {}", userId, e.getMessage());
            }
        }
        int bm25Channels = 0;
        String bm25Query = null;
        try { bm25Query = JiebaTokenizer.tokenize(query); } catch (Exception ignored) {}
        if (bm25Query != null && !bm25Query.isBlank()) {
            try {
                List<UserMemory> b = memoryMapper.findAnchorBm25(userId, bm25Query, topN, includeGlobal, projectIds);
                if (b != null && !b.isEmpty()) {
                    List<Long> order = new ArrayList<>();
                    for (UserMemory m : b) {
                        if (m != null && m.getId() != null) { byId.putIfAbsent(m.getId(), m); bm25Ids.add(m.getId()); order.add(m.getId()); }
                    }
                    if (!order.isEmpty()) { channelLists.add(new RrfFusion.WeightedList<>(order, LLMKEY_BM25_WEIGHT)); bm25Channels = 1; }
                }
            } catch (Exception e) {
                log.warn("LLM_KEY anchor BM25 检索失败 userId={}: {}", userId, e.getMessage());
            }
        }
        if (byId.isEmpty()) {
            if (trace != null) trace.populateChannels(0, 0);  // 两通道都空，仅记通道计数
            return null;
        }

        // ③ RRF 融合（向量权 > 词法权）→ top-N 候选
        List<UserMemory> candidates = RrfFusion.sortByScoreDesc(RrfFusion.fuseWeighted(channelLists, LLMKEY_RRF_K)).stream()
                .limit(topN)
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            if (trace != null) trace.populateChannels(vecIds.size(), bm25Ids.size());
            return null;
        }
        log.info("LLM_KEY 粗筛 userId={} qHalfs={} 向量通道={} bm25={} → top-{} 候选 首key[{}]",
                userId, qHalfs.size(), vecChannels, bm25Channels, candidates.size(), candidates.get(0).getMemoryKey());
        if (trace != null) {
            for (UserMemory m : candidates) {
                boolean inVec = vecIds.contains(m.getId());
                boolean inBm = bm25Ids.contains(m.getId());
                trace.channelById.put(m.getId(), (inVec && inBm) ? "both" : inVec ? "vector" : "bm25");
            }
            trace.candidates = candidates;
            trace.populateChannels(vecIds.size(), bm25Ids.size());
        }

        // ④ rerank=false：直接注 top-N（跳 LLM 精排）
        if (!rerank) return formatMemories(userId, candidates);

        // ⑤ rerank=true：双维度精排，选中 key 列表缓存（TTL 60s，同 query 命中跳 LLM 精排）
        final List<UserMemory> candFinal = candidates;
        String queryHash = Integer.toHexString(query.hashCode());
        List<String> selectedKeys = queryCache.getRerankKeys(readScope, queryHash, () -> {
            List<UserMemory> sel = selectRelevantMemories(readScope, query, candFinal);
            return sel == null ? List.of()
                    : sel.stream().map(UserMemory::getMemoryKey).filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        });
        if (trace != null) trace.selectedKeys = selectedKeys;   // null/空也记（前端区分"精排判无关"vs"未精排"）
        if (selectedKeys == null || selectedKeys.isEmpty()) return null;
        // 缓存命中或刚算：用选中 key 过滤当前候选装配注入（key 维；block 维已在 selectRelevantMemories 内筛）
        Set<String> keySet = new java.util.HashSet<>(selectedKeys);
        List<UserMemory> sel = candidates.stream()
                .filter(m -> keySet.contains(m.getMemoryKey()))
                .collect(Collectors.toList());
        return sel.isEmpty() ? null : formatMemories(userId, sel);
    }

    /** 全量召回（LLM_FULL_CONTEXT 模式，限 scope）。
     *  超阈值两阶段（V33）：scope 内记忆条数 > fullContextThreshold（默认 20，0=禁用）且 query 非空时，
     *  不再全量灌 value，改"先加载全部 key→LLM 选相关 key→只装相关 value+category"（复用 selectRelevantKeys），
     *  省 token + 治联想泄漏。LLM 选空/异常 → 不注入（宁缺毋滥，与 hybrid 兜底一致）。
     *  query 为空或未超阈值 → 走原全量。 */
    public String buildFullContext(MemoryScope readScope, String query) {
        List<UserMemory> memories = memoryMapper.findFullContext(readScope.userId(), CONF_THRESHOLD,
                readScope.includeGlobal(), readScope.safeProjectIds());
        if (memories.isEmpty()) return null;
        int threshold = systemSettingService.getMemoryFullContextThreshold();
        if (threshold > 0 && memories.size() > threshold && query != null && !query.isBlank()) {
            log.info("fullContext 记忆 {} 条 > 阈值 {}，触发两阶段 LLM 筛 key userId={}", memories.size(), threshold, readScope.userId());
            return filterRelevantKeys(readScope, query, memories);
        }
        return formatMemories(readScope.userId(), memories);
    }

    /** 公共格式化：FLAGGED 行（conflictId!=null）加 [⚠️冲突] 前缀 + counterpart 值。
     *  key 展示语言按系统设置 key-language：ZH=中文 key_zh（空回退英文）/ EN=英文 key（默认）/ BOTH=中文(英文)。 */
    private String formatMemories(Long userId, List<UserMemory> memories) {
        String keyLang = systemSettingService.getMemoryKeyLanguage();
        StringBuilder sb = new StringBuilder();
        for (UserMemory m : memories) {
            if (m.getConflictId() != null) {
                String counterpart = findCounterpartValue(userId, m.getConflictId(), m.getId());
                sb.append("[⚠️冲突] ").append(formatLine(m, keyLang))
                  .append(" （与\"").append(counterpart).append("\"冲突，待澄清）\n");
            } else {
                sb.append(formatLine(m, keyLang)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String formatLine(UserMemory m, String keyLang) {
        String keyZh = m.getMemoryKeyZh();
        boolean hasZh = keyZh != null && !keyZh.isBlank();
        String keyLabel;
        if ("ZH".equals(keyLang)) {
            keyLabel = hasZh ? keyZh : m.getMemoryKey();           // 中文标签（空→英文兜底）
        } else if ("BOTH".equals(keyLang)) {
            keyLabel = hasZh ? keyZh + "(" + m.getMemoryKey() + ")" : m.getMemoryKey();  // 中英双选（空→仅英文）
        } else {
            keyLabel = m.getMemoryKey();                            // EN（默认）
        }
        return "[" + m.getCategory() + "] " + keyLabel + ": " + m.getMemoryValue();
    }

    private String findCounterpartValue(Long userId, Long conflictId, Long selfId) {
        List<UserMemory> same = memoryMapper.findByConflictId(conflictId).stream()
                .filter(m -> !selfId.equals(m.getId()) && userId.equals(m.getUserId()))
                .toList();
        if (same.isEmpty()) return "?";
        return same.stream().map(UserMemory::getMemoryValue).collect(Collectors.joining("/"));
    }

    // ============================ 用户自服务（查询/删除/scope 编辑）============================

    /** 列出当前用户全部记忆（按 updatedAt 倒序，跨全部 scope），带冲突标志 + scope 归属（is_global + projectIds）。 */
    public List<UserMemoryVO> listMemories(Long userId) {
        List<UserMemory> memories = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .orderByDesc(UserMemory::getUpdatedAt));
        if (memories.isEmpty()) return List.of();
        // 批量加载项目归属（避免 N+1）
        List<Long> ids = memories.stream().map(UserMemory::getId).toList();
        Map<Long, List<Long>> projByMemory = new HashMap<>();
        for (MemoryProjectRow row : memoryMapper.findProjectIdsByMemories(ids)) {
            projByMemory.computeIfAbsent(row.getMemoryId(), k -> new ArrayList<>()).add(row.getProjectId());
        }
        return memories.stream()
                .map(m -> toVO(userId, m, projByMemory.getOrDefault(m.getId(), Collections.emptyList())))
                .collect(Collectors.toList());
    }

    /** 删除单条记忆（ownership 校验：非本人返回 false）。 */
    public boolean deleteMemory(Long userId, Long id) {
        UserMemory m = memoryMapper.selectById(id);
        if (m == null || !userId.equals(m.getUserId())) {
            return false;
        }
        boolean ok = memoryMapper.deleteById(id) > 0;
        if (ok) queryCache.evictUser(userId);
        return ok;
    }

    /** 清空当前用户全部记忆，返回删除条数。 */
    public int clearMemories(Long userId) {
        int n = memoryMapper.delete(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId));
        if (n > 0) queryCache.evictUser(userId);
        return n;
    }

    /**
     * 批量删除记忆（ownership 过滤：只删本人 id，非本人/不存在静默跳过）。
     * 用 inQuery 二次校验归属，避免直接信任前端 id → 越权删他人。
     */
    public int deleteMemories(Long userId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Long> owned = memoryMapper.selectObjs(new LambdaQueryWrapper<UserMemory>()
                        .select(UserMemory::getId)
                        .eq(UserMemory::getUserId, userId)
                        .in(UserMemory::getId, ids))
                .stream()
                .map(o -> (Long) o)
                .toList();
        if (owned.isEmpty()) {
            return 0;
        }
        int n = memoryMapper.deleteBatchIds(owned);
        if (n > 0) queryCache.evictUser(userId);
        return n;
    }

    // ============================ scope 编辑（V33）============================

    /** 回显单条记忆的 scope 归属（is_global + projectIds）。面板编辑用。 */
    public MemoryScopeVO getMemoryScopes(Long userId, Long memoryId) {
        UserMemory m = ensureOwned(userId, memoryId);
        return new MemoryScopeVO(m.getIsGlobal(),
                memoryMapper.findProjectIdsByMemory(memoryId));
    }

    /** 替换单条记忆的全部 scope 归属（is_global + projectIds 全删全插）。flush 用户全前缀。
     *  统一覆盖「升级为 global」/「加入项目」/「关闭 global」。不 re-embed（仅改可见性）。 */
    @org.springframework.transaction.annotation.Transactional
    public MemoryScopeVO updateMemoryScopes(Long userId, Long memoryId, boolean isGlobal, List<Long> projectIds) {
        UserMemory m = ensureOwned(userId, memoryId);
        memoryMapper.updateIsGlobal(memoryId, isGlobal);
        memoryMapper.deleteMemoryProjects(memoryId);
        List<Long> pids = projectIds == null ? Collections.emptyList() : new ArrayList<>(projectIds);
        if (!pids.isEmpty()) {
            memoryMapper.insertMemoryProjects(memoryId, pids);
        }
        queryCache.evictUser(userId);
        log.info("updateMemoryScopes memoryId={} isGlobal={} projectIds={}", memoryId, isGlobal, pids);
        return new MemoryScopeVO(isGlobal, pids);
    }

    // ============================ 行内编辑（M1）============================

    /** 行内编辑记忆：改 memory_key / key_zh / value / block_label，按需重算 value embedding + anchor。
     *  home-aware 重复检查：改 key 须确保同 user 同 home 内无其它同 key clean 行（排除自身），撞约束返 CONFLICT 业务错误不静默。
     *  重 embed 条件：value 变 → 重算 value 向量；key/key_zh/block 变 → 重算 anchor（entities 不动，旧词袋沿用）。
     *  embed 失败 COALESCE 保旧向量（韧性，同 applyClean）。home/scope/可见性标签/conflict 不动（编辑不改归属）。 */
    @org.springframework.transaction.annotation.Transactional
    public UserMemoryVO updateMemory(Long userId, Long memoryId, MemoryEditRequest req) {
        UserMemory m = ensureOwned(userId, memoryId);
        if (req == null) {
            throw new com.superprogrammer.common.exception.BusinessException(
                    com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST, "请求体为空");
        }
        String newKey = req.getMemoryKey() == null ? null : req.getMemoryKey().trim();
        String newKeyZh = req.getMemoryKeyZh();
        String newValue = req.getMemoryValue();
        String newBlock = req.getBlockLabel();
        if (newKey == null || newKey.isBlank()) {
            throw new com.superprogrammer.common.exception.BusinessException(
                    com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST, "记忆 key 不能为空");
        }
        if (newValue == null || newValue.isBlank()) {
            throw new com.superprogrammer.common.exception.BusinessException(
                    com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST, "记忆值不能为空");
        }

        // home-aware 重复检查（排除自身；同 user 同 home 同 key 的 clean 行）
        List<UserMemory> sames = memoryMapper.findCleanByHomeKey(userId, newKey, m.getHomeProjectId());
        for (UserMemory s : sames) {
            if (!s.getId().equals(memoryId)) {
                throw new com.superprogrammer.common.exception.BusinessException(
                        com.superprogrammer.common.exception.ErrorCode.CONFLICT,
                        "同归属下已存在记忆 key：" + newKey);
            }
        }

        boolean keyChanged = !newKey.equals(m.getMemoryKey());
        boolean keyZhChanged = !Objects.equals(newKeyZh, m.getMemoryKeyZh());
        boolean blockChanged = !Objects.equals(newBlock, m.getBlockLabel());
        boolean valueChanged = !Objects.equals(newValue, m.getMemoryValue());

        // 条件重 embed（未变 → null → COALESCE 保留旧向量）
        String valueHalfvec = null;
        if (valueChanged) {
            try {
                float[] vec = llmGateway.embed(newValue, RagConfig.MEMORY_EMBED_MODEL);
                valueHalfvec = HalfVecUtil.toHalfVec(vec);
            } catch (Exception e) {
                log.warn("edit value embed 失败 id={}: {}", memoryId, e.getMessage());
            }
        }
        String anchorHalfvec = null;
        String anchorTokens = null;
        if (keyChanged || keyZhChanged || blockChanged) {
            AnchorEmbedding anchor = embedAnchor(newBlock, newKeyZh, newKey, m.getEntities());
            anchorHalfvec = anchor.halfvec();
            anchorTokens = anchor.tokens();
        }

        int n = memoryMapper.updateMemoryEdit(memoryId, newKey, newKeyZh, newValue, newBlock,
                valueHalfvec, anchorHalfvec, anchorTokens);
        if (n <= 0) {
            throw new com.superprogrammer.common.exception.BusinessException(
                    com.superprogrammer.common.exception.ErrorCode.NOT_FOUND, "记忆不存在或无权操作");
        }
        queryCache.evictUser(userId);
        log.info("updateMemory id={} keyChanged={} valueChanged={} blockChanged={} keyZhChanged={}",
                memoryId, keyChanged, valueChanged, blockChanged, keyZhChanged);
        UserMemory updated = memoryMapper.selectById(memoryId);
        return toVO(userId, updated, memoryMapper.findProjectIdsByMemory(memoryId));
    }

    private UserMemory ensureOwned(Long userId, Long memoryId) {
        UserMemory m = memoryMapper.selectById(memoryId);
        if (m == null || !userId.equals(m.getUserId())) {
            throw new com.superprogrammer.common.exception.BusinessException(com.superprogrammer.common.exception.ErrorCode.NOT_FOUND, "记忆不存在或无权操作");
        }
        return m;
    }

    /** scope 归属回显 DTO（内部 record，控制器转 R）。 */
    public record MemoryScopeVO(Boolean isGlobal, List<Long> projectIds) {}

    // ============================ 老数据回填（V31/V32 迁移）============================

    /**
     * 回填老记忆（entities / memory_key_zh / anchor_embedding 任一为空）的召回词袋 + 中文标签 + anchor 锚点：
     * <ul>
     *   <li>entities 或 key_zh 缺失：batch LLM 抽 entities（召回词袋：标签+变体+专名）+ key_zh → 落 entities + memory_key_zh</li>
     *   <li>anchor 缺失：embedAnchor（block+key_zh+key+entities）落 anchor 两列（向量+词法 token）</li>
     * </ul>
     * 幂等：无实体的行落 entities='[]'、无 key_zh 落 ''（标记已处理）；anchor embed 失败落空→下次重试，成功后 anchor_embedding 非空跳过。
     * 不 bump updated_at（不扰动记忆列表排序）。同步实现（可单测），admin 端点经 {@link #backfillEntitiesAsync()} 异步触发避免 HTTP 超时。
     * @return 实际更新行数。
     */
    public int backfillEntities() {
        List<UserMemory> rows = memoryMapper.findBackfillCandidates();
        if (rows.isEmpty()) {
            log.info("memoryBackfill 无待回填行（全部已处理）");
            return 0;
        }
        log.info("memoryBackfill 待回填 {} 行，batch=20", rows.size());
        int updated = 0;
        java.util.Set<Long> touchedUsers = new java.util.HashSet<>();
        int batchSize = 20;
        for (int i = 0; i < rows.size(); i += batchSize) {
            List<UserMemory> chunk = rows.subList(i, Math.min(i + batchSize, rows.size()));
            try {
                // 仅 entities/key_zh 缺失的行灌 LLM 抽取（anchor-only 行省 LLM，用既有 entities/key_zh）
                List<UserMemory> toExtract = chunk.stream()
                        .filter(m -> m.getEntities() == null || m.getMemoryKeyZh() == null)
                        .collect(Collectors.toList());
                java.util.Map<Long, MemoryConflictJudge.BackfillRow> res =
                        toExtract.isEmpty() ? java.util.Collections.emptyMap() : judge.batchExtractEntities(toExtract);
                for (UserMemory m : chunk) {
                    MemoryConflictJudge.BackfillRow r = res.get(m.getId());
                    final String entitiesJson;
                    final String keyZh;
                    if (r != null) {
                        List<String> e = r.entities() == null ? List.of() : r.entities();
                        String json = (e.isEmpty()) ? "[]" : entitiesJson(e);
                        if (json == null) json = "[]"; // entitiesJson 失败兜底
                        entitiesJson = json;
                        keyZh = (r.keyZh() == null) ? "" : r.keyZh();   // 抽不出落 ""（幂等标记，重跑跳过）
                        memoryMapper.updateEntitiesAndKeyZh(m.getId(), entitiesJson, keyZh);
                    } else {
                        entitiesJson = m.getEntities();
                        keyZh = m.getMemoryKeyZh();
                    }
                    // anchor 两列：用最终 entities/key_zh 落（向量+词法 token）；embed 失败 COALESCE 保留旧值→下次重试
                    AnchorEmbedding ae = embedAnchor(m.getBlockLabel(), keyZh, m.getMemoryKey(), entitiesJson);
                    memoryMapper.updateAnchor(m.getId(), ae.halfvec(), ae.tokens());
                    touchedUsers.add(m.getUserId());
                    updated++;
                }
                log.info("memoryBackfill chunk {}-{} 完成（抽取 {} 行）", i, i + chunk.size(), toExtract.size());
            } catch (Exception ex) {
                log.warn("memoryBackfill chunk 失败 i={} {}: {}", i, chunk.size(), ex.getMessage());
            }
        }
        touchedUsers.forEach(queryCache::evictUser);
        log.info("memoryBackfill 完成，更新 {} 行", updated);
        return updated;
    }

    /**
     * 提交记忆异步任务到 memoryTaskExecutor。
     * <p>池+队列满（AbortPolicy）→ 捕获 RejectedExecutionException 记 WARN，绝不抛回调用线程
     * （admin 端点 fire-and-forget，回退会钉死 admin 请求线程，见 RB-001 根因②）。
     */
    private void submitMemoryTask(Runnable task, String taskName) {
        try {
            memoryTaskExecutor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ree) {
            log.warn("{} 异步任务被拒（memoryTaskExecutor 池满），本次跳过: {}", taskName, ree.getMessage());
        }
    }

    /** 异步触发回填（admin 端点用，fire-and-forget 到 memoryTaskExecutor，避免 HTTP 超时）。 */
    public void backfillEntitiesAsync() {
        submitMemoryTask(() -> {
            try {
                backfillEntities();
            } catch (Exception e) {
                log.error("memoryBackfill 异步任务失败", e);
            }
        }, "memoryBackfill");
    }

    /** 全量重抽老记忆 entities 词袋（维护用，与 {@link #backfillEntities()} 互补）：
     *  无视 NULL 过滤，按当前 extract prompt 为所有老记忆重抽 entities（含上位词），保留 key_zh 不动，
     *  重 embed anchor（block + 老 key_zh + key + 新 entities）。用途：entities 抽取 prompt 改动后让老数据吃新规则
     *  （回填只补 NULL，已填行 '[]'/'' 非空 → 跳过，改 prompt 后老数据无法刷新）。
     *  <p>fail-safe：LLM 抽空的行保留旧 entities（防回归），与回填的 '[]' 幂等标记不同——重抽是手动触发，护数据优先于幂等。
     *  <p>不 bump updated_at（不扰动记忆列表排序）。admin 端点经 {@link #reextractEntitiesAsync()} 异步触发避免 HTTP 超时。
     * @return 实际重抽行数（抽空跳过的不计）。 */
    public int reextractEntities() {
        List<UserMemory> rows = memoryMapper.findAllMemories();
        if (rows.isEmpty()) {
            log.info("memoryReextract 无记忆行");
            return 0;
        }
        log.info("memoryReextract 待重抽 {} 行（按当前 prompt 刷 entities，保留 key_zh）", rows.size());
        int updated = 0, skipped = 0;
        java.util.Set<Long> touchedUsers = new java.util.HashSet<>();
        int batchSize = 20;
        for (int i = 0; i < rows.size(); i += batchSize) {
            List<UserMemory> chunk = rows.subList(i, Math.min(i + batchSize, rows.size()));
            try {
                java.util.Map<Long, MemoryConflictJudge.BackfillRow> res = judge.batchExtractEntities(chunk);
                for (UserMemory m : chunk) {
                    MemoryConflictJudge.BackfillRow r = res.get(m.getId());
                    if (r == null || r.entities() == null || r.entities().isEmpty()) {
                        skipped++;   // LLM 抽空 → 保留旧 entities（防回归），不覆写
                        continue;
                    }
                    String json = entitiesJson(r.entities());
                    if (json == null) { skipped++; continue; }
                    String oldKeyZh = m.getMemoryKeyZh();   // 保留老 key_zh（重抽只刷 entities 词袋）
                    memoryMapper.updateEntitiesAndKeyZh(m.getId(), json, oldKeyZh);
                    // entities 变 → anchor 文本变 → 重 embed（用老 key_zh + 新 entities）
                    AnchorEmbedding ae = embedAnchor(m.getBlockLabel(), oldKeyZh, m.getMemoryKey(), json);
                    memoryMapper.updateAnchor(m.getId(), ae.halfvec(), ae.tokens());
                    touchedUsers.add(m.getUserId());
                    updated++;
                }
                log.info("memoryReextract chunk {}-{} 完成", i, i + chunk.size());
            } catch (Exception ex) {
                log.warn("memoryReextract chunk 失败 i={} {}: {}", i, chunk.size(), ex.getMessage());
            }
        }
        touchedUsers.forEach(queryCache::evictUser);
        log.info("memoryReextract 完成，重抽 {} 行，跳过 {} 行（LLM 抽空保留旧值）", updated, skipped);
        return updated;
    }

    /** 异步触发全量重抽（admin 端点用，fire-and-forget 到 memoryTaskExecutor，避免 HTTP 超时）。 */
    public void reextractEntitiesAsync() {
        submitMemoryTask(() -> {
            try {
                reextractEntities();
            } catch (Exception e) {
                log.error("memoryReextract 异步任务失败", e);
            }
        }, "memoryReextract");
    }

    /** 异步清理历史 KEEP_BOTH 脏数据（conflict 已 RESOLVED 但行仍带 conflict_id 的残留）。
     *  admin 端点用，fire-and-forget 到 memoryTaskExecutor。进度见后端日志 memoryCleanup。 */
    public void cleanupResolvedResidueAsync() {
        submitMemoryTask(() -> {
            try {
                conflictService.cleanupResolvedResidue();
            } catch (Exception e) {
                log.error("memoryCleanup 异步任务失败", e);
            }
        }, "memoryCleanup");
    }

    private UserMemoryVO toVO(Long userId, UserMemory m, List<Long> projectIds) {
        UserMemoryVO vo = new UserMemoryVO();
        vo.setId(m.getId());
        vo.setCategory(m.getCategory());
        vo.setMemoryKey(m.getMemoryKey());
        vo.setMemoryKeyZh(m.getMemoryKeyZh());
        vo.setMemoryValue(m.getMemoryValue());
        vo.setBlockLabel(m.getBlockLabel());
        vo.setSource(m.getSource());
        vo.setConfidence(m.getConfidence());
        vo.setCreatedAt(m.getCreatedAt());
        vo.setUpdatedAt(m.getUpdatedAt());
        vo.setConflictId(m.getConflictId());
        vo.setConflictStatus(m.getConflictId() != null ? "FLAGGED" : null);
        vo.setConflictWith(m.getConflictId() != null ? findCounterpartValue(userId, m.getConflictId(), m.getId()) : null);
        vo.setIsGlobal(m.getIsGlobal());
        vo.setProjectIds(projectIds);
        vo.setHomeProjectId(m.getHomeProjectId());
        return vo;
    }
}
