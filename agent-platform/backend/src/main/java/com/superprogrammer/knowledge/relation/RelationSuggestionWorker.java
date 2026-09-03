package com.superprogrammer.knowledge.relation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.config.RagRecallProperties;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeDocumentRelation;
import com.superprogrammer.knowledge.entity.KnowledgeDocumentRelationSuggestion;
import com.superprogrammer.knowledge.entity.RagRetrievalLog;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentRelationMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentRelationSuggestionMapper;
import com.superprogrammer.knowledge.mapper.RagRetrievalLogMapper;
import com.superprogrammer.knowledge.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C1 关联建议 worker（规格 §3.3，防图谱维护膨胀）：复用既有 trace 共召回记录——
 * 统计近窗口内「同一 query 的注入证据里两文档共现」次数 ≥ 阈值 且 未建边 且 无既有建议行 →
 * 写 PENDING 建议；已有 PENDING 行 → 续算（count/样本 hash/last_seen 刷新）。
 * <b>只建议、绝不自动建边</b>（采纳是人的决定，见 RelationSuggestionService）。
 *
 * <p>数据源：{@code rag_retrieval_logs.evidence_l2}（SUPPORTED 且 evidence 非空的行；
 * 每行注入证据去重出 documentId 集 → 无序对组合）。跨库对丢弃（首版关联仅同库）；
 * 已有边（四类型任一、任一方向）的对跳过；ADOPTED/IGNORED 建议行占用 uq_kdrs → 不再重提（用户已裁决）。
 *
 * <p>幂等：无状态全窗口重扫（窗口有界：lookbackDays 默认 14 天），cursor 分批；
 * uq_kdrs(kb, a, b) 兜底并发/重跑唯一性。开关 {@code rag.recall.relation.suggestion-enabled}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RelationSuggestionWorker {

    private static final Long TENANT_ID = 1L;
    private static final int BATCH = 500;

    private final RagRetrievalLogMapper logMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentRelationMapper relationMapper;
    private final KnowledgeDocumentRelationSuggestionMapper suggestionMapper;
    private final RagRecallProperties recallProps;
    private final ObjectMapper objectMapper;

    /** 每日 03:17（避开整点扎堆）：低频后台统计，不与业务高峰重叠。 */
    @Scheduled(cron = "0 17 3 * * *")
    public void scheduledRun() {
        try {
            run();
        } catch (Exception e) {
            log.error("关联建议 worker 失败（不影响主链，次日重试）: {}", e.getMessage(), e);
        }
    }

    /** 全窗口重扫（测试/运维手动触发入口）。返回新建议数。 */
    public int run() {
        long t0 = System.currentTimeMillis();
        RagRecallProperties.Relation cfg = recallProps.getRelation();
        if (!cfg.isSuggestionEnabled()) {
            return 0;
        }
        OffsetDateTime since = OffsetDateTime.now().minusDays(Math.max(1, cfg.getSuggestionLookbackDays()));

        // 1 扫 trace：pair → 统计（窗口内全量，cursor 分批）
        Map<String, PairStat> stats = new LinkedHashMap<>();
        long cursor = 0L;
        int logsScanned = 0;
        while (true) {
            List<RagRetrievalLog> batch = logMapper.selectList(
                    new LambdaQueryWrapper<RagRetrievalLog>()
                            .eq(RagRetrievalLog::getCragVerdict, "SUPPORTED")
                            .isNotNull(RagRetrievalLog::getEvidenceL2)
                            .ge(RagRetrievalLog::getCreatedAt, since)
                            .gt(RagRetrievalLog::getId, cursor)
                            .orderByAsc(RagRetrievalLog::getId)
                            .last("LIMIT " + BATCH));
            if (batch.isEmpty()) {
                break;
            }
            for (RagRetrievalLog l : batch) {
                cursor = l.getId();
                logsScanned++;
                accumulatePairs(l, stats);
            }
            if (batch.size() < BATCH) {
                break;
            }
        }
        if (stats.isEmpty()) {
            log.info("关联建议 worker：窗口 {} 天扫 {} 条 trace 无共现对", cfg.getSuggestionLookbackDays(), logsScanned);
            return 0;
        }

        // 2 文档存在性 + 同库过滤（批查，逻辑删文档自然缺席）
        Set<Long> docIds = new HashSet<>();
        stats.values().forEach(s -> {
            docIds.add(s.docA);
            docIds.add(s.docB);
        });
        Map<Long, Long> docKb = new HashMap<>();
        if (!docIds.isEmpty()) {
            documentMapper.selectBatchIds(docIds).forEach(d -> docKb.put(d.getId(), d.getKbId()));
        }

        // 3 已有边过滤（单 SQL 双向 IN；四类型任一存在即跳过）
        Set<String> edgedPairs = loadEdgedPairs(docIds);

        // 4 已有建议行（任一状态：PENDING=续算，ADOPTED/IGNORED=已裁决不重提）
        Map<String, KnowledgeDocumentRelationSuggestion> existing = loadExistingSuggestions(docIds);

        int created = 0;
        int updated = 0;
        for (PairStat s : stats.values()) {
            Long kbA = docKb.get(s.docA);
            Long kbB = docKb.get(s.docB);
            if (kbA == null || kbB == null || !kbA.equals(kbB)) {
                continue;   // 任一端已删，或跨库对（首版关联仅同库）
            }
            if (s.count < Math.max(2, cfg.getSuggestionMinCoRecall())) {
                continue;
            }
            String pairKey = kbA + ":" + s.docA + ":" + s.docB;
            if (edgedPairs.contains(pairKey)) {
                continue;
            }
            KnowledgeDocumentRelationSuggestion row = existing.get(pairKey);
            if (row == null) {
                KnowledgeDocumentRelationSuggestion insert = new KnowledgeDocumentRelationSuggestion();
                insert.setTenantId(TENANT_ID);
                insert.setKbId(kbA);
                insert.setDocIdA(s.docA);
                insert.setDocIdB(s.docB);
                insert.setCoRecallCount(s.count);
                insert.setSampleQueryHash(s.sampleQueryHash);
                insert.setStatus(KnowledgeDocumentRelationSuggestion.STATUS_PENDING);
                insert.setLastSeenAt(OffsetDateTime.now());
                suggestionMapper.insert(insert);
                created++;
            } else if (KnowledgeDocumentRelationSuggestion.STATUS_PENDING.equals(row.getStatus())) {
                row.setCoRecallCount(s.count);
                row.setSampleQueryHash(s.sampleQueryHash);
                row.setLastSeenAt(OffsetDateTime.now());
                suggestionMapper.updateById(row);
                updated++;
            }
        }
        log.info("关联建议 worker 完成：窗口 {} 天 / {} 条 trace / {} 候选对 → 新建议 {} / 续算 {} / 耗时 {}ms",
                cfg.getSuggestionLookbackDays(), logsScanned, stats.size(), created, updated,
                System.currentTimeMillis() - t0);
        return created;
    }

    /** 单条 trace：注入证据去重 docId → 无序对 (a<b) 累计。evidence 解析失败跳过该条（脏数据不阻断）。 */
    private void accumulatePairs(RagRetrievalLog log, Map<String, PairStat> stats) {
        List<Long> docIds = parseEvidenceDocIds(log.getEvidenceL2());
        if (docIds.size() < 2) {
            return;
        }
        String queryHash = HashUtil.sha256(log.getQuery() == null ? "" : log.getQuery());
        for (int i = 0; i < docIds.size(); i++) {
            for (int j = i + 1; j < docIds.size(); j++) {
                long a = docIds.get(i);
                long b = docIds.get(j);
                if (a == b) {
                    continue;
                }
                if (a > b) {   // a<b 规范化（对齐 ck_kdrs_pair）
                    long t = a;
                    a = b;
                    b = t;
                }
                final long ka = a;
                final long kb = b;
                stats.computeIfAbsent(a + ":" + b, k -> new PairStat(ka, kb)).bump(queryHash);
            }
        }
    }

    /** evidence_l2 JSON 行抽 documentId 去重升序。 */
    private List<Long> parseEvidenceDocIds(String evidenceJson) {
        if (evidenceJson == null || evidenceJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = objectMapper.readTree(evidenceJson);
            if (!arr.isArray()) {
                return List.of();
            }
            Set<Long> ids = new java.util.TreeSet<>();
            for (JsonNode row : arr) {
                JsonNode docId = row.get("documentId");
                if (docId != null && docId.canConvertToLong()) {
                    ids.add(docId.asLong());
                }
            }
            return new ArrayList<>(ids);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 双端都在 docIds 内的既有边 → "kb:a:b" 集（双向任一存在都算已建边）。 */
    private Set<String> loadEdgedPairs(Set<Long> docIds) {
        Set<String> pairs = new HashSet<>();
        if (docIds.isEmpty()) {
            return pairs;
        }
        List<KnowledgeDocumentRelation> edges = relationMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentRelation>()
                        .in(KnowledgeDocumentRelation::getDocId, docIds)
                        .in(KnowledgeDocumentRelation::getRelatedDocId, docIds));
        for (KnowledgeDocumentRelation e : edges) {
            pairs.add(e.getKbId() + ":" + Math.min(e.getDocId(), e.getRelatedDocId())
                    + ":" + Math.max(e.getDocId(), e.getRelatedDocId()));
        }
        return pairs;
    }

    /** 既有建议行（uq: kb+a+b）→ ADOPTED/IGNORED 占位即跳过（用户已裁决），PENDING 续算。 */
    private Map<String, KnowledgeDocumentRelationSuggestion> loadExistingSuggestions(Set<Long> docIds) {
        Map<String, KnowledgeDocumentRelationSuggestion> map = new HashMap<>();
        if (docIds.isEmpty()) {
            return map;
        }
        List<KnowledgeDocumentRelationSuggestion> rows = suggestionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentRelationSuggestion>()
                        .in(KnowledgeDocumentRelationSuggestion::getDocIdA, docIds)
                        .in(KnowledgeDocumentRelationSuggestion::getDocIdB, docIds));
        for (KnowledgeDocumentRelationSuggestion r : rows) {
            map.put(r.getKbId() + ":" + r.getDocIdA() + ":" + r.getDocIdB(), r);
        }
        return map;
    }

    /** 无库 pair 统计（kb 后置解析；key=合成去重键，仅 worker 内部用）。 */
    private static final class PairStat {
        final long docA;
        final long docB;
        int count;
        String sampleQueryHash;

        PairStat(long docA, long docB) {
            this.docA = docA;
            this.docB = docB;
        }

        void bump(String queryHash) {
            count++;
            sampleQueryHash = queryHash;
        }
    }
}
