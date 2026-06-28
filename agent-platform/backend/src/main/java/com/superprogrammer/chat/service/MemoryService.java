package com.superprogrammer.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.superprogrammer.knowledge.service.RagConfig;
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
    /** 关键词召回分词上限（避免 SQL OR 列表过长）。 */
    private static final int KEYWORD_MAX = 8;
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
            log.info("processMemory userId={} sessionId={} writeTarget={} 抽取facts={}", userId, sessionId, writeTargetProjectId, facts);
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
            log.warn("记忆抽取失败: {}", e.getMessage(), e);
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
        log.info("processFacts userId={} sessionId={} writeTarget={} facts={}", userId, sessionId, writeTargetProjectId, facts);
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
                log.info("processFacts 重复跳过 key={} value={}", fk, fv);
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
            log.info("processFacts batch judge block={} facts={} cmpSet={}", block, bucket.size(), cmpSet.size());
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
                log.info("processFacts 预去重跳过 key={} value={}", f.key(), f.value());
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
                log.info("applyClean 细化更新 key={} id={} value={}", f.key(), existing.getId(), f.value());
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

    // ============================ 注入（下游 LLM system msg）============================

    /**
     * 记忆召回入口：按系统设置 retrievalMode 分流。V33 带读 scope（扁平开关集）。
     *  LLM_FULL_CONTEXT（默认）：scope 内全量 confidence≥0.5 记忆灌入（向后兼容：global scope=今天行为）。
     *  EMBEDDING_VECTOR：query embed → pgvector top-K 余弦检索（限 scope），仅注入强相关记忆；无命中 → 返回 null。
     *  VECTOR_KEYWORD：向量 top-K ∪ 关键词(实体列)召回（限 scope）；并集 0 命中 → LLM-key 兜底。
     */
    public String buildMemoryContext(MemoryScope readScope, String query) {
        String mode = systemSettingService.getMemoryRetrievalMode();
        String keyLang = systemSettingService.getMemoryKeyLanguage();
        int threshold = systemSettingService.getMemoryFullContextThreshold();
        String context;
        if ("EMBEDDING_VECTOR".equals(mode)) {
            context = buildVectorContext(readScope, query);
        } else if ("VECTOR_KEYWORD".equals(mode)) {
            context = buildHybridContext(readScope, query);
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
            log.info("记忆注入 userId={} scope={} mode={} keyLang={} threshold={} query.len={} → 注入{}字符 首条[{}]",
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
        String context = buildMemoryContext(readScope, query);
        boolean twoStage = "LLM_FULL_CONTEXT".equals(mode) && threshold > 0
                && total != null && total > threshold
                && query != null && !query.isBlank();
        return com.superprogrammer.chat.dto.MemoryContextPreviewVO.builder()
                .mode(mode).keyLanguage(keyLang).threshold(threshold)
                .totalMemories(total).twoStage(twoStage).context(context).build();
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

    /** 两阶段召回公共筛（V38 双维度 key + block_label）：三路径共用（fullContext 超阈值 / hybrid 0命中兜底 / LLM_KEY 精排）。
     * <ol>
     *   <li>distinct memory_key → judge.selectRelevantKeys 选相关 key（召回优先）；</li>
     *   <li>distinct block_label（null→""）→ judge.selectRelevantBlocks 选相关 block；</li>
     *   <li>交集装配：key∈相关 且 block∈相关。</li>
     * </ol>
     * 剔重在 Java 端（Stream.distinct），不新增 DB 查询。容错（召回优先，宁多勿漏）：
     * key 筛挂/空 → 单走 block；block 筛挂/空 → 单走 key；都挂/空 → null。注入只留 key+value（丢 category/block/entities/confidence 噪声由 formatLine 控）。
     * key 匹配始终用英文 memory_key；key 语言设置只影响 formatLine 展示。 */
    private String filterRelevantKeys(MemoryScope readScope, String query, List<UserMemory> allMemories) {
        if (allMemories == null || allMemories.isEmpty()) return null;
        Long userId = readScope.userId();
        try {
            // distinct memory_key（同 key 多行去重，省 token）→ selectRelevantKeys 灌 key:value
            List<UserMemory> distinctByKey = new ArrayList<>(allMemories.stream()
                    .collect(Collectors.toMap(UserMemory::getMemoryKey, m -> m, (a, b) -> a, java.util.LinkedHashMap::new))
                    .values());
            List<String> distinctBlocks = allMemories.stream()
                    .map(m -> m.getBlockLabel() == null ? "" : m.getBlockLabel())
                    .distinct().collect(Collectors.toList());

            // key 筛：异常或空 → 视作该维无效（单走 block）
            Set<String> relevantKeys = selectDim(userId, "key", () -> judge.selectRelevantKeys(query, distinctByKey));
            Set<String> relevantBlocks = selectDim(userId, "block", () -> judge.selectRelevantBlocks(query, distinctBlocks));
            if (relevantKeys == null && relevantBlocks == null) return null;   // 两维都挂/空

            final Set<String> fk = relevantKeys;
            final Set<String> fb = relevantBlocks;
            List<UserMemory> sel = allMemories.stream()
                    .filter(m -> (fk == null || fk.contains(m.getMemoryKey()))
                              && (fb == null || fb.contains(m.getBlockLabel() == null ? "" : m.getBlockLabel())))
                    .collect(Collectors.toList());
            if (sel.isEmpty()) return null;
            return formatMemories(userId, sel);
        } catch (Exception e) {
            log.warn("filterRelevantKeys 双维度筛失败 userId={}: {} → 不注入", userId, e.getMessage(), e);
            return null;
        }
    }

    /** 单维度 LLM 筛封装：异常或空结果 → 返回 null（视作该维无效，交另一维兜底）。非空 → set。 */
    private Set<String> selectDim(Long userId, String dim, java.util.function.Supplier<List<String>> selector) {
        try {
            List<String> r = selector.get();
            return (r == null || r.isEmpty()) ? null : new java.util.HashSet<>(r);
        } catch (Exception e) {
            log.warn("filterRelevantKeys {} 维筛挂，单走另一维 userId={}: {}", dim, userId, e.getMessage());
            return null;
        }
    }

    /**
     * query 分词（VECTOR_KEYWORD 关键词召回用）：CJK 连续段切 2-gram，字母数字段整段保留。
     * 噪声 gram（如"去玩"）天然自滤——它们不是任何实体的子串，ILIKE 撞不上 entities 列。
     * 去重 + 截断 KEYWORD_MAX，避免 SQL OR 列表过长。
     */
    private static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) return List.of();
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
        return out.stream().distinct().limit(KEYWORD_MAX).collect(Collectors.toList());
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
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
     * 回填老记忆（entities IS NULL 或 memory_key_zh IS NULL）的召回词袋 + 中文标签：
     * batch LLM 抽 entities（召回词袋：标签+变体+专名）+ key_zh（≤20条/批）→ 落 entities + memory_key_zh。
     * 幂等：无实体的行落 entities='[]'、无 key_zh 落 ''（标记已处理），重跑跳过；不 bump updated_at（不扰动记忆列表）。
     * 同步实现（可单测），admin 端点经 {@link #backfillEntitiesAsync()} 异步触发避免 HTTP 超时。
     * @return 实际更新行数。
     */
    public int backfillEntities() {
        List<UserMemory> rows = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .isNull(UserMemory::getEntities)
                .or()
                .isNull(UserMemory::getMemoryKeyZh));
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
                java.util.Map<Long, MemoryConflictJudge.BackfillRow> res = judge.batchExtractEntities(chunk);
                for (UserMemory m : chunk) {
                    MemoryConflictJudge.BackfillRow r = res.get(m.getId());
                    List<String> e = (r == null) ? List.of() : r.entities();
                    String json = (e.isEmpty()) ? "[]" : entitiesJson(e);
                    if (json == null) json = "[]"; // entitiesJson 失败兜底
                    // key_zh 抽不出落 ""（非 null，幂等标记，重跑跳过）
                    String keyZh = (r == null || r.keyZh() == null) ? "" : r.keyZh();
                    memoryMapper.updateEntitiesAndKeyZh(m.getId(), json, keyZh);
                    touchedUsers.add(m.getUserId());
                    updated++;
                }
                log.info("memoryBackfill chunk {}-{} 完成", i, i + chunk.size());
            } catch (Exception ex) {
                log.warn("memoryBackfill chunk 失败 i={} {}: {}", i, chunk.size(), ex.getMessage());
            }
        }
        touchedUsers.forEach(queryCache::evictUser);
        log.info("memoryBackfill 完成，更新 {} 行", updated);
        return updated;
    }

    /** 异步触发回填（admin 端点用，fire-and-forget 到 memoryTaskExecutor，避免 HTTP 超时）。 */
    public void backfillEntitiesAsync() {
        memoryTaskExecutor.execute(() -> {
            try {
                backfillEntities();
            } catch (Exception e) {
                log.error("memoryBackfill 异步任务失败", e);
            }
        });
    }

    /** 异步清理历史 KEEP_BOTH 脏数据（conflict 已 RESOLVED 但行仍带 conflict_id 的残留）。
     *  admin 端点用，fire-and-forget 到 memoryTaskExecutor。进度见后端日志 memoryCleanup。 */
    public void cleanupResolvedResidueAsync() {
        memoryTaskExecutor.execute(() -> {
            try {
                conflictService.cleanupResolvedResidue();
            } catch (Exception e) {
                log.error("memoryCleanup 异步任务失败", e);
            }
        });
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
        return vo;
    }
}
