package com.superprogrammer.knowledge.global;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.superprogrammer.knowledge.config.KbSummaryProperties;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeBaseSummary;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseSummaryMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * C7 库级摘要（L-KB）生成 Worker（规格 §9.1，WP4 Step1）。
 * 低峰 cron 扫全库：触发判定（文档数变更 ≥阈值% 或距上次 >staleDays）→ 全文档 L1 摘要
 * map 分批浓缩 → reduce 合成库级摘要+主题清单 → 版本化落表。
 *
 * 成本节流（坑点预判）：单库重试 ≤maxAttempts，超限置 ERROR 行待手动（触发判定跳过 ERROR，
 * 沿用上一 READY 版继续服务——生成失败不伤检索）；开关关=零 LLM 调用。
 * 泄露面：summary 仅落库供 Service 内部注入 prompt，本类无任何对外读接口（规格 §9.3）。
 * 计费归户：@Scheduled 线程无请求上下文，按 KB 创建者归户 map/reduce 调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbSummaryWorker {

    private static final int SUMMARY_MAX_CHARS = 2000;
    private static final int TOPIC_MAX_CHARS = 30;
    private static final int TOPIC_MAX_COUNT = 20;
    private static final int SUMMARY_INPUT_MAX_CHARS = 300;

    private static final String MAP_SYSTEM = """
            你是知识库编目员。将输入的一批文档摘要浓缩为要点清单：每文档输出一行「《标题》：核心要点（≤40字）」，\
            保留数字、版本、日期等可核查事实，不评论不扩展，不输出清单以外的任何内容。""";
    private static final String REDUCE_SYSTEM = """
            你是知识库总编。基于各批要点合成库级总览：覆盖主要主题、关键制度/流程、重要数字与版本。\
            只使用要点中出现的信息，不编造。输出 JSON（不要 markdown 代码块）：\
            {"summary":"≤2000字总览","topics":["主题词"]}，topics ≤20 个、每词 ≤30 字。""";

    private final KnowledgeBaseMapper baseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseSummaryMapper summaryMapper;
    private final LlmGateway llmGateway;
    private final KbSummaryProperties props;
    private final ObjectMapper objectMapper;

    /** 低峰窗口（默认每天 04:30）。cron 绑定与 KbSummaryProperties.cron 同键。 */
    @Scheduled(cron = "${rag.global.summary.cron:0 30 4 * * *}")
    public void poll() {
        if (!props.isEnabled()) {
            return;
        }
        List<KnowledgeBase> bases = baseMapper.selectList(new LambdaQueryWrapper<>());
        int generated = 0;
        for (KnowledgeBase kb : bases) {
            try {
                if (generateIfDue(kb)) {
                    generated++;
                }
            } catch (Exception e) {
                log.error("库级摘要处理异常 kbId={}: {}", kb.getId(), e.getMessage(), e);
            }
        }
        if (generated > 0) {
            log.info("[L-KB] 低峰生成完成 本轮新版本 {} 个库", generated);
        }
    }

    /** @return true=本轮生成了新版本（含 ERROR 行）。 */
    boolean generateIfDue(KnowledgeBase kb) {
        List<KnowledgeDocument> docs = loadL1Docs(kb.getId());
        if (docs.isEmpty()) {
            return false;   // 无 L1 文档库跳过（未解析/未 INDEXED 不硬凑）
        }
        KnowledgeBaseSummary latest = latestRow(kb.getId());
        if (!due(latest, docs.size())) {
            return false;
        }
        String lastError = null;
        for (int attempt = 1; attempt <= props.getMaxAttempts(); attempt++) {
            try {
                SummaryOutcome outcome = generateOnce(kb, docs);
                insertRow(kb, latest, docs.size(), attempt, outcome, "READY");
                log.info("[L-KB] 库级摘要生成 kbId={} v{} docs={} batches={}",
                        kb.getId(), (latest == null ? 0 : latest.getVersion()) + 1, docs.size(), outcome.batchCount());
                return true;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("[L-KB] 库级摘要生成失败 kbId={} attempt={}/{}: {}",
                        kb.getId(), attempt, props.getMaxAttempts(), e.getMessage());
            }
        }
        insertErrorRow(kb, latest, docs.size(), lastError);
        return true;
    }

    // ---- 触发判定（坑点预判：成本失控）----

    private boolean due(KnowledgeBaseSummary latest, int docCount) {
        if (latest == null) {
            return true;   // 首次生成
        }
        if ("ERROR".equals(latest.getStatus())) {
            return false;  // 连续失败待手动，本轮跳过（旧 READY 版继续服务）
        }
        Integer prevCount = statsInt(latest, "docCount");
        if (prevCount != null && prevCount > 0) {
            int changedPct = Math.abs(docCount - prevCount) * 100 / prevCount;
            if (changedPct >= props.getChangeThresholdPct()) {
                return true;
            }
        } else {
            return true;   // 上一版统计缺失→视为需重生成
        }
        OffsetDateTime generatedAt = latest.getGeneratedAt();
        return generatedAt == null
                || generatedAt.isBefore(OffsetDateTime.now().minusDays(props.getStaleDays()));
    }

    // ---- map-reduce ----

    private SummaryOutcome generateOnce(KnowledgeBase kb, List<KnowledgeDocument> docs) {
        List<String> batchOutputs = new ArrayList<>();
        int batchCount = 0;
        for (int i = 0; i < docs.size(); i += props.getBatchSize()) {
            List<KnowledgeDocument> batch = docs.subList(i, Math.min(i + props.getBatchSize(), docs.size()));
            batchOutputs.add(mapBatch(kb, batch));
            batchCount++;
        }
        JsonNode reduced = reduce(kb, batchOutputs);
        String summary = reduced.path("summary").asText("").trim();
        if (summary.isEmpty()) {
            throw new IllegalStateException("reduce 输出 summary 为空");
        }
        if (summary.length() > SUMMARY_MAX_CHARS) {
            summary = summary.substring(0, SUMMARY_MAX_CHARS);
        }
        ArrayNode topics = objectMapper.createArrayNode();
        JsonNode rawTopics = reduced.path("topics");
        if (rawTopics.isArray()) {
            for (JsonNode t : rawTopics) {
                if (topics.size() >= TOPIC_MAX_COUNT) {
                    break;
                }
                String topic = t.asText("").trim();
                if (!topic.isEmpty()) {
                    topics.add(topic.length() > TOPIC_MAX_CHARS
                            ? topic.substring(0, TOPIC_MAX_CHARS) : topic);
                }
            }
        }
        return new SummaryOutcome(summary, topics.toString(), batchCount);
    }

    private String mapBatch(KnowledgeBase kb, List<KnowledgeDocument> batch) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeDocument doc : batch) {
            String summary = l1Summary(doc);
            if (summary.length() > SUMMARY_INPUT_MAX_CHARS) {
                summary = summary.substring(0, SUMMARY_INPUT_MAX_CHARS);
            }
            sb.append("《").append(doc.getTitle() == null ? "未命名" : doc.getTitle()).append("》")
                    .append(summary).append('\n');
        }
        return chat(kb, MAP_SYSTEM, sb.toString(), props.getMapMaxTokens());
    }

    private JsonNode reduce(KnowledgeBase kb, List<String> batchOutputs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < batchOutputs.size(); i++) {
            sb.append("== 第 ").append(i + 1).append(" 批要点 ==\n")
                    .append(batchOutputs.get(i)).append('\n');
        }
        String content = chat(kb, REDUCE_SYSTEM, sb.toString(), props.getReduceMaxTokens());
        try {
            return objectMapper.readTree(stripFences(content));
        } catch (Exception e) {
            throw new IllegalStateException("reduce 输出非合法 JSON: " + truncate(e.getMessage(), 120));
        }
    }

    private String chat(KnowledgeBase kb, String system, String user, int maxTokens) {
        LlmRequest req = LlmRequest.builder()
                .model(props.getModel())
                .messages(List.of(new LlmMessage("system", system), new LlmMessage("user", user)))
                .temperature(0.3)
                .maxTokens(maxTokens)
                .stream(false)
                .build();
        LlmResponse resp = llmGateway.chat(req, kb.getCreatedBy());   // 计费归户 KB 创建者
        if (resp == null || resp.getContent() == null || resp.getContent().isBlank()) {
            throw new IllegalStateException("LLM 返回空内容");
        }
        return resp.getContent().trim();
    }

    // ---- 落表 ----

    private void insertRow(KnowledgeBase kb, KnowledgeBaseSummary latest, int docCount,
                           int attempt, SummaryOutcome outcome, String status) {
        KnowledgeBaseSummary row = baseRow(kb, latest, docCount, attempt, status);
        row.setSummary(outcome.summary());
        row.setTopics(outcome.topics());
        summaryMapper.insert(row);
    }

    private void insertErrorRow(KnowledgeBase kb, KnowledgeBaseSummary latest, int docCount, String error) {
        KnowledgeBaseSummary row = baseRow(kb, latest, docCount, props.getMaxAttempts(), "ERROR");
        try {
            JsonNode stats = objectMapper.readTree(row.getStats());
            if (stats instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
                obj.put("error", truncate(error, 300));
                row.setStats(obj.toString());
            }
        } catch (Exception ignored) {
            // stats 构造在 baseRow 内自带合法 JSON，此分支防御性
        }
        summaryMapper.insert(row);
        log.error("[L-KB] 库级摘要连续 {} 次失败置 ERROR 待手动 kbId={} v{}: {}",
                props.getMaxAttempts(), kb.getId(), row.getVersion(), truncate(error, 200));
    }

    private KnowledgeBaseSummary baseRow(KnowledgeBase kb, KnowledgeBaseSummary latest,
                                         int docCount, int attempt, String status) {
        KnowledgeBaseSummary row = new KnowledgeBaseSummary();
        row.setTenantId(1L);
        row.setKbId(kb.getId());
        row.setVersion((latest == null || latest.getVersion() == null ? 0 : latest.getVersion()) + 1);
        row.setStatus(status);
        row.setGeneratedAt(OffsetDateTime.now());
        row.setCreatedBy(kb.getCreatedBy());
        row.setStats("{\"docCount\":" + docCount + ",\"batchCount\":"
                + (docCount + props.getBatchSize() - 1) / props.getBatchSize()
                + ",\"model\":" + (props.getModel() == null ? "\"default\"" : "\"" + props.getModel() + "\"")
                + ",\"attempt\":" + attempt + "}");
        return row;
    }

    // ---- 查询与小工具 ----

    private List<KnowledgeDocument> loadL1Docs(Long kbId) {
        LambdaQueryWrapper<KnowledgeDocument> w = new LambdaQueryWrapper<>();
        w.eq(KnowledgeDocument::getKbId, kbId)
                .eq(KnowledgeDocument::getStatus, "INDEXED")
                .isNotNull(KnowledgeDocument::getL1Metadata)
                .select(KnowledgeDocument::getId, KnowledgeDocument::getTitle,
                        KnowledgeDocument::getL1Metadata);
        return documentMapper.selectList(w);
    }

    private KnowledgeBaseSummary latestRow(Long kbId) {
        LambdaQueryWrapper<KnowledgeBaseSummary> w = new LambdaQueryWrapper<>();
        w.eq(KnowledgeBaseSummary::getKbId, kbId)
                .orderByDesc(KnowledgeBaseSummary::getVersion)
                .last("LIMIT 1");
        return summaryMapper.selectOne(w);
    }

    private String l1Summary(KnowledgeDocument doc) {
        try {
            String summary = objectMapper.readTree(doc.getL1Metadata()).path("summary").asText("");
            return summary.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private Integer statsInt(KnowledgeBaseSummary row, String field) {
        try {
            JsonNode v = objectMapper.readTree(row.getStats()).path(field);
            return v.isNumber() ? v.asInt() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripFences(String content) {
        String s = content == null ? "" : content.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                s = s.substring(firstNl + 1, lastFence).trim();
            }
        }
        return s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    record SummaryOutcome(String summary, String topics, int batchCount) {}
}
