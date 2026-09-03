package com.superprogrammer.knowledge.global;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.config.GlobalAnswerProperties;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeBaseSummary;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseSummaryMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.service.internal.CitationChecker;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * C7 全局问答分支（WP4 Step2，规格 §9.2）：「总结全库」类问题不走 chunk 检索，
 * 取库内可见文档 L1 摘要全集 map 分批提要点 → reduce 合成答案，引用降文档级（[n]《标题》）。
 * 对齐 GraphRAG Global Search 思路——以 L1 为社区单元，不做 Leiden 聚类。
 *
 * <p>降级链（坑点预判·延迟）：map+reduce 总预算 {@code timeoutMs}（默认 30s），超时/LLM 失败/
 * 引用越界重生成仍失败 → 仅返回 L-KB 概览段+「缩小范围」提示（degraded=true）。
 *
 * <p>泄露面（规格 §9.3）：L-KB 摘要仅注入 prompt；map 文档集按可见集过滤（成员只见其可见
 * 文档的 L1 摘要）；引用白名单=实际参与 map 的文档序号（CitationChecker 文档级模式）。
 * 计费归户当前提问用户（请求上下文，区别于 Worker 归户 KB 创建者）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalAnswerStrategy {

    private static final int SUMMARY_INPUT_MAX_CHARS = 300;
    private static final int MAP_POOL_SIZE = 2;   // 批间并行上限（坑点：50 文档 4 批串行太慢，>2 打爆 LLM 网关）

    /** map 并行池：守护线程（不阻 JVM 退出），固定 2 并发=计划口径「并行 ≤2」。 */
    private static final ExecutorService MAP_POOL = Executors.newFixedThreadPool(MAP_POOL_SIZE, r -> {
        Thread t = new Thread(r, "rag-global-map");
        t.setDaemon(true);
        return t;
    });

    private static final String MAP_SYSTEM = """
            你是知识库编目员。输入是一批带编号的文档摘要（格式 [n]《标题》：摘要）。逐文档提炼要点，\
            每文档输出一行「[n]《标题》：核心要点（≤40字）」，保留原编号 [n] 不变，\
            保留数字、版本、日期等可核查事实，不评论不扩展，不输出清单以外的任何内容。""";
    private static final String REDUCE_SYSTEM = """
            你是知识库总编。基于各批要点回答用户问题：只使用要点中出现的信息；引用参与文档用其编号 \
            [n]（形如 [2]《标题》），不得引用未列出的编号；未覆盖的方面如实说明。答案 ≤800 字，\
            直接作答（不要概览开头，系统会另行拼装库概览段），不输出 markdown 代码块。""";

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseSummaryMapper summaryMapper;
    private final LlmGateway llmGateway;
    private final GlobalAnswerProperties props;
    private final CitationChecker citationChecker;
    private final ObjectMapper objectMapper;

    /** 参与 map 的文档（引用白名单基础；ordinal=列表序号+1，答案 [n] 与此对应）。 */
    public record GlobalDoc(Long docId, String title, String l1Summary) {
    }

    /** 全局回答产物：answer 已含概览段拼接；cited=通过文档级校验的引用序号。 */
    public record GlobalResult(String answer, List<GlobalDoc> docs, List<Integer> cited,
                               int batchCount, boolean degraded, boolean overviewUsed,
                               String overviewGeneratedAt) {
    }

    /** 轻量勘察（调试面板 GLOBAL 分支标识：零 LLM，只数文档和批数）。 */
    public record Inspection(int docCount, int batchCount, boolean overviewReady) {
    }

    /** 勘察：参与文档数 / map 批数 / L-KB 概览是否就绪（不调任何 LLM）。 */
    public Inspection inspect(Long kbId, boolean allDocs, List<Long> visibleDocIds) {
        List<GlobalDoc> docs = loadDocs(kbId, allDocs, visibleDocIds);
        return new Inspection(docs.size(), batchCount(docs.size()), overviewRow(kbId) != null);
    }

    /**
     * 全局回答主入口。
     *
     * @return null=开关关闭（调用方回落常规检索管道）；degraded=true 表示降级产物（仅概览+提示）
     */
    public GlobalResult answer(KnowledgeBase kb, String query, Long userId,
                               boolean allDocs, List<Long> visibleDocIds, boolean multiKbNarrowed) {
        if (!props.isEnabled()) {
            return null;
        }
        long deadlineNanos = System.nanoTime() + props.getTimeoutMs() * 1_000_000L;
        List<GlobalDoc> docs = loadDocs(kb.getId(), allDocs, visibleDocIds);
        KnowledgeBaseSummary overview = overviewRow(kb.getId());
        int batches = batchCount(docs.size());

        String body = null;
        List<Integer> cited = List.of();
        boolean degraded;
        if (docs.isEmpty()) {
            degraded = true;   // 无可用 L1 → 直接降级（零 LLM 调用）
        } else {
            degraded = false;
            try {
                List<String> mapOutputs = mapAll(docs, userId, deadlineNanos);
                body = reduce(query, mapOutputs, docs.size(), userId, deadlineNanos);
                // 文档级引用校验：越界 → 带指令重生成一次 → 仍越界降级（复用 chunk 级三段式口径）
                cited = citationChecker.extractAndCheckDocLevel(body, ordinals(docs));
                if (cited == null) {
                    body = reduce(query, mapOutputs, docs.size(), userId, deadlineNanos, true);
                    cited = citationChecker.extractAndCheckDocLevel(body, ordinals(docs));
                    if (cited == null) {
                        log.warn("[GLOBAL] 引用越界重生成仍失败 kbId={} docs={}", kb.getId(), docs.size());
                        body = null;
                        cited = List.of();
                        degraded = true;
                    }
                }
            } catch (TimeoutException e) {
                log.warn("[GLOBAL] map-reduce 超时（>{}ms）kbId={} docs={}", props.getTimeoutMs(), kb.getId(), docs.size());
                degraded = true;
                body = null;
                cited = List.of();
            } catch (Exception e) {
                log.warn("[GLOBAL] map-reduce 失败 kbId={}: {}", kb.getId(), e.getMessage());
                degraded = true;
                body = null;
                cited = List.of();
            }
        }
        return compose(kb, docs, body, cited, batches, degraded, overview, multiKbNarrowed);
    }

    // ---- map（批间并行 ≤2，总预算内） ----

    private List<String> mapAll(List<GlobalDoc> docs, Long userId, long deadlineNanos)
            throws TimeoutException, InterruptedException {
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < docs.size(); i += props.getBatchSize()) {
            List<GlobalDoc> batch = docs.subList(i, Math.min(i + props.getBatchSize(), docs.size()));
            int baseOrdinal = i;   // 批内编号=全集序号（跨批不重排，reduce 引用全集稳定）
            futures.add(CompletableFuture.supplyAsync(() -> mapBatch(batch, baseOrdinal, userId, deadlineNanos), MAP_POOL));
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(remainingMs(deadlineNanos, "map 等待"), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            // 批内失败解包冒泡（预算耗尽的 TimeoutException 包装 / LLM 异常原样）→ 上层统一降级
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("map 批次执行失败", e.getCause());
        }
        List<String> out = new ArrayList<>(futures.size());
        for (CompletableFuture<String> f : futures) {
            out.add(f.join());   // allOf 成功后 join 必不抛（异常已在 join 冒泡为执行异常 → 上层 catch）
        }
        return out;
    }

    private String mapBatch(List<GlobalDoc> batch, int baseOrdinal, Long userId, long deadlineNanos) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            GlobalDoc d = batch.get(i);
            String summary = d.l1Summary() == null ? "" : d.l1Summary();
            if (summary.length() > SUMMARY_INPUT_MAX_CHARS) {
                summary = summary.substring(0, SUMMARY_INPUT_MAX_CHARS);
            }
            sb.append('[').append(baseOrdinal + i + 1).append("]《")
                    .append(d.title() == null ? "未命名" : d.title()).append("》：").append(summary).append('\n');
        }
        return chat(MAP_SYSTEM, sb.toString(), props.getMapMaxTokens(), userId, deadlineNanos);
    }

    // ---- reduce（合成答案） ----

    private String reduce(String query, List<String> mapOutputs, int docCount, Long userId,
                          long deadlineNanos) {
        return reduce(query, mapOutputs, docCount, userId, deadlineNanos, false);
    }

    private String reduce(String query, List<String> mapOutputs, int docCount, Long userId,
                          long deadlineNanos, boolean citationRetry) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mapOutputs.size(); i++) {
            sb.append("== 第 ").append(i + 1).append(" 批要点 ==\n").append(mapOutputs.get(i)).append('\n');
        }
        sb.append("\n用户问题：").append(query == null ? "" : query);
        if (citationRetry) {
            sb.append("\n\n（注意：上一次回答引用了不存在的编号。合法编号仅为 [1] 到 [")
                    .append(docCount).append("]，请严格只引用这些编号。）");
        }
        return chat(REDUCE_SYSTEM, sb.toString(), props.getReduceMaxTokens(), userId, deadlineNanos);
    }

    // ---- 拼装 ----

    private GlobalResult compose(KnowledgeBase kb, List<GlobalDoc> docs, String body, List<Integer> cited,
                                 int batchCount, boolean degraded, KnowledgeBaseSummary overview,
                                 boolean multiKbNarrowed) {
        StringBuilder answer = new StringBuilder();
        String overviewGeneratedAt = null;
        if (overview != null && overview.getSummary() != null && !overview.getSummary().isBlank()) {
            overviewGeneratedAt = overview.getGeneratedAt() == null ? null
                    : overview.getGeneratedAt().toLocalDate().toString();
            answer.append("【库概览】（来自库级摘要 L-KB");
            if (overviewGeneratedAt != null) {
                answer.append("，生成于 ").append(overviewGeneratedAt);
            }
            answer.append("）\n").append(overview.getSummary()).append("\n\n");
        }
        if (body != null) {
            answer.append("【要点综述】\n").append(body);
        } else {
            answer.append(docs.isEmpty()
                    ? "该库暂无可用的文档摘要（文档未解析或未建索引），无法生成全局概览。"
                    : "全局要点合成未完成（超时或引用校验失败），已降级为仅库级概览。建议缩小问题范围（如指定主题或文档）后再试。");
        }
        if (multiKbNarrowed) {
            answer.append("\n\n> 已仅对首个知识库《").append(kb.getName() == null ? "" : kb.getName())
                    .append("》生成全局概览；如需其他库请单独选择。");
        }
        return new GlobalResult(answer.toString(), docs, cited, batchCount, degraded,
                overviewGeneratedAt != null, overviewGeneratedAt);
    }

    // ---- 查询与小工具 ----

    /** 参与文档全集：可见集 ∩ INDEXED ∩ 有 L1（含 ATTACHMENT——L1 口径与 L-KB Worker 一致）。 */
    private List<GlobalDoc> loadDocs(Long kbId, boolean allDocs, List<Long> visibleDocIds) {
        if (!allDocs && (visibleDocIds == null || visibleDocIds.isEmpty())) {
            return List.of();
        }
        LambdaQueryWrapper<KnowledgeDocument> w = new LambdaQueryWrapper<>();
        w.eq(KnowledgeDocument::getKbId, kbId)
                .eq(KnowledgeDocument::getStatus, "INDEXED")
                .isNotNull(KnowledgeDocument::getL1Metadata);
        if (!allDocs) {
            w.in(KnowledgeDocument::getId, visibleDocIds);
        }
        w.select(KnowledgeDocument::getId, KnowledgeDocument::getTitle, KnowledgeDocument::getL1Metadata)
                .orderByAsc(KnowledgeDocument::getId);   // 序号稳定（引用 [n] 跨重试一致）
        return documentMapper.selectList(w).stream()
                .map(d -> new GlobalDoc(d.getId(), d.getTitle(), l1Summary(d.getL1Metadata())))
                .toList();
    }

    /** 最新 READY 概览行（ERROR/无历史 → null：概览段缺席，不伤回答主体）。 */
    private KnowledgeBaseSummary overviewRow(Long kbId) {
        LambdaQueryWrapper<KnowledgeBaseSummary> w = new LambdaQueryWrapper<>();
        w.eq(KnowledgeBaseSummary::getKbId, kbId)
                .eq(KnowledgeBaseSummary::getStatus, "READY")
                .orderByDesc(KnowledgeBaseSummary::getVersion)
                .last("LIMIT 1");
        return summaryMapper.selectOne(w);
    }

    private String l1Summary(String l1Metadata) {
        try {
            return objectMapper.readTree(l1Metadata).path("summary").asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private Set<Integer> ordinals(List<GlobalDoc> docs) {
        Set<Integer> out = new HashSet<>();
        for (int i = 1; i <= docs.size(); i++) {
            out.add(i);
        }
        return out;
    }

    private int batchCount(int docCount) {
        return docCount == 0 ? 0 : (docCount + props.getBatchSize() - 1) / props.getBatchSize();
    }

    private long remainingMs(long deadlineNanos, String stage) {
        long ms = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (ms <= 0) {
            throw new RuntimeException(new TimeoutException(stage + " 预算耗尽"));
        }
        return ms;
    }

    /** 单次 LLM 调用：per-call 超时=min(剩余预算, 总预算)，provider 侧中止 + 网关异常统一冒泡上层降级。 */
    private String chat(String system, String user, int maxTokens, Long userId, long deadlineNanos) {
        LlmRequest req = LlmRequest.builder()
                .model(props.getModel())
                .messages(List.of(new LlmMessage("system", system), new LlmMessage("user", user)))
                .temperature(0.3)
                .maxTokens(maxTokens)
                .stream(false)
                .timeoutMs((int) Math.min(remainingMs(deadlineNanos, "LLM 调用"), props.getTimeoutMs()))
                .build();
        LlmResponse resp = llmGateway.chat(req, userId);
        if (resp == null || resp.getContent() == null || resp.getContent().isBlank()) {
            throw new IllegalStateException("LLM 返回空内容");
        }
        return resp.getContent().trim();
    }

    /** 静态池优雅关闭（应用停机不 hung 非守护场景；守护线程本身可硬停）。 */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        MAP_POOL.shutdown();
    }
}
