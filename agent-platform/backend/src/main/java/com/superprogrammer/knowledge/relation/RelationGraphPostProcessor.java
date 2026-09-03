package com.superprogrammer.knowledge.relation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.knowledge.entity.KnowledgeDocumentRelation;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentRelationMapper;
import com.superprogrammer.knowledge.mapper.RagRetrievalQueryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C1 step6.5 关系图后处理（规格 §3.2）：rerank 后对最终命中集 H 做一次关系扩展。
 *
 * <p>职责边界：本类只做「边扫描 → 目标分类 → 可见性静默过滤 → 有效 L2 装载」的<b>计划</b>；
 * 打分/重排/合并进 topK 由 {@code RagRetrievalService.expandRelations} 完成（MAY 要重打分，属检索主链）。
 *
 * <p>触发语义（存储四类型，检索按命中端方向解释；规格 §3.1 表）：
 * <ul>
 *   <li>出边 MUST_CITE(A→X)：命中 A ⇒ X 强制带出（RELATION_MUST）</li>
 *   <li>入边 MUST_BE_CITED(X→A)：命中 A ⇒ X 强制带出（等价反向读）</li>
 *   <li>出边 MAY_CITE(A→X)：命中 A ⇒ X 追加重打分（RELATION_MAY，过阈值才进）</li>
 *   <li>入边 MAY_BE_CITED(X→A)：命中 A ⇒ X 仅进「相关文档」区，不进主上下文</li>
 * </ul>
 *
 * <p>硬限制：1 跳——只扫触达 H 的边，带出文档自身的边不再传导（A→B→C 的 C 不出现，
 * 结构上保证：目标 ∉ H 才带出，且带出后不回扫）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelationGraphPostProcessor {

    public static final String INJECTED_BY_MUST = "RELATION_MUST";
    public static final String INJECTED_BY_MAY = "RELATION_MAY";

    private final KnowledgeDocumentRelationMapper relationMapper;
    private final RagRetrievalQueryMapper queryMapper;

    /** 关系带出的候选节点（打分由调用方赋：MUST=topK 最高分+ε，MAY=重打分实值）。 */
    public record RelationNode(Long nodeId, Long documentId, Long parentId, String title,
                               String content, String contentHash) {
    }

    /** 「相关文档」区条目（MAY_BE_CITED 反向解释，不进证据）。 */
    public record RelatedDoc(Long documentId, String title, String relationType) {
    }

    /**
     * @param mustNodes            强制带出节点（per-doc 已截 perDocL2Cap）
     * @param mayNodes             按需引用节点（同上；调用方重打分后过滤）
     * @param relatedDocs          相关文档区（仅标题）
     * @param edgesScanned         触达 H 的边数（计量）
     * @param droppedByPermission  可见性过滤丢弃的目标文档数（静默，防权限探测侧信道）
     */
    public record ExpansionPlan(List<RelationNode> mustNodes, List<RelationNode> mayNodes,
                                List<RelatedDoc> relatedDocs, int edgesScanned,
                                int droppedByPermission) {
        public boolean isEmpty() {
            return mustNodes.isEmpty() && mayNodes.isEmpty() && relatedDocs.isEmpty();
        }
    }

    /**
     * 边扫描 + 计划生成。一次批量 IN 查正反两向（1 SQL）；H 空 → 直接空计划（零回归路径）。
     *
     * @param hitDocIds    最终命中文档集 H（rerank 后）
     * @param allDocs      调用方可见集 allDocs（admin/owner 全库 → 目标全可见）
     * @param visibleDocIds 可见集 docIds（per-KB ACL 判定结果，H 同口径——同库单判定复用）
     * @param perDocL2Cap  每带出文档的 L2 节点上限（同 gatherL2Candidates 的 perDocL2Cap）
     */
    public ExpansionPlan planExpansion(Long kbId, Set<Long> hitDocIds, boolean allDocs,
                                       List<Long> visibleDocIds, int perDocL2Cap) {
        long t0 = System.currentTimeMillis();
        if (hitDocIds == null || hitDocIds.isEmpty()) {
            return new ExpansionPlan(List.of(), List.of(), List.of(), 0, 0);
        }
        // 1 跳硬限制：只扫 doc_id/related_doc_id 触达 H 的边（单 SQL 双向 IN）
        List<KnowledgeDocumentRelation> edges = relationMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentRelation>()
                        .eq(KnowledgeDocumentRelation::getKbId, kbId)
                        .and(w -> w.in(KnowledgeDocumentRelation::getDocId, hitDocIds)
                                .or().in(KnowledgeDocumentRelation::getRelatedDocId, hitDocIds)));

        // 目标分类（LinkedHashSet 保序防重复边；目标已在 H → 跳过=去重，天然防 A⇄B 双向边重复注入）
        Set<Long> mustTargets = new LinkedHashSet<>();
        Set<Long> mayTargets = new LinkedHashSet<>();
        Set<Long> relatedTargets = new LinkedHashSet<>();
        for (KnowledgeDocumentRelation e : edges) {
            boolean docHit = hitDocIds.contains(e.getDocId());
            boolean relHit = hitDocIds.contains(e.getRelatedDocId());
            String type = e.getRelationType();
            if (docHit && !hitDocIds.contains(e.getRelatedDocId())) {
                switch (type) {
                    case KnowledgeDocumentRelation.TYPE_MUST_CITE -> mustTargets.add(e.getRelatedDocId());
                    case KnowledgeDocumentRelation.TYPE_MAY_CITE -> mayTargets.add(e.getRelatedDocId());
                    default -> { /* 出边 MUST_BE_CITED/MAY_BE_CITED：被动语义由对端触发，对端未命中 */ }
                }
            }
            if (relHit && !hitDocIds.contains(e.getDocId())) {
                switch (type) {
                    case KnowledgeDocumentRelation.TYPE_MUST_BE_CITED -> mustTargets.add(e.getDocId());
                    case KnowledgeDocumentRelation.TYPE_MAY_BE_CITED -> relatedTargets.add(e.getDocId());
                    default -> { /* 入边 MUST_CITE/MAY_CITE：主动方=对端未命中，无触发 */ }
                }
            }
        }
        // MUST 优先：同目标同时挂 MUST/MAY 时按 MUST 处理（强语义覆盖弱语义）
        mayTargets.removeAll(mustTargets);

        // 可见性复校（同库单判定：H 能见 = vs 已过 per-KB ACL，目标同口径比对 vs 即可）
        // 无权限目标静默丢弃——不报错、不提示存在（防权限探测侧信道，规格 §3.2.4）
        Set<Long> visible = allDocs ? null : new LinkedHashSet<>(visibleDocIds == null ? List.of() : visibleDocIds);
        int dropped = 0;
        dropped += filterInvisible(mustTargets, visible);
        dropped += filterInvisible(mayTargets, visible);
        dropped += filterInvisible(relatedTargets, visible);

        // 有效 L2 装载（文档级有效性 JOIN 在 SQL；悬挂边/过期文档自然空）
        List<RelationNode> mustNodes = loadCapped(kbId, mustTargets, perDocL2Cap);
        List<RelationNode> mayNodes = loadCapped(kbId, mayTargets, perDocL2Cap);

        List<RelatedDoc> relatedDocs = new ArrayList<>();
        if (!relatedTargets.isEmpty()) {
            for (var row : queryMapper.listValidDocTitles(kbId, new ArrayList<>(relatedTargets))) {
                relatedDocs.add(new RelatedDoc(row.getDocumentId(), row.getTitle(),
                        KnowledgeDocumentRelation.TYPE_MAY_BE_CITED));
            }
        }

        ExpansionPlan plan = new ExpansionPlan(mustNodes, mayNodes, relatedDocs,
                edges.size(), dropped);
        log.info("step6.5 关系扩展 kb={} 边={} MUST带出={} MAY带出={} 相关文档={} 权限丢弃={} 耗时={}ms",
                kbId, edges.size(), mustNodes.size(), mayNodes.size(), relatedDocs.size(),
                dropped, System.currentTimeMillis() - t0);
        return plan;
    }

    /** 不可见目标剔除（返回剔除数）。visible=null 表示 allDocs 全可见。 */
    private static int filterInvisible(Set<Long> targets, Set<Long> visible) {
        if (visible == null || targets.isEmpty()) {
            return 0;
        }
        int before = targets.size();
        targets.removeIf(t -> !visible.contains(t));
        return before - targets.size();
    }

    /** 拉目标的 L2 节点并按 perDocL2Cap 截断（SQL 按 document_id,id 有序 → 取前 N 稳定）。 */
    private List<RelationNode> loadCapped(Long kbId, Set<Long> docIds, int perDocL2Cap) {
        if (docIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> perDocCount = new LinkedHashMap<>();
        List<RelationNode> out = new ArrayList<>();
        for (var r : queryMapper.fetchRelationDocL2(kbId, new ArrayList<>(docIds))) {
            if (perDocCount.merge(r.getDocumentId(), 1, Integer::sum) > perDocL2Cap) {
                continue;
            }
            out.add(new RelationNode(r.getNodeId(), r.getDocumentId(), r.getParentId(),
                    r.getTitle(), r.getContent(), r.getContentHash()));
        }
        return out;
    }
}
