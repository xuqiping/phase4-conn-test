package com.superprogrammer.knowledge.relation;

import com.superprogrammer.knowledge.dto.RagQueryRow;
import com.superprogrammer.knowledge.entity.KnowledgeDocumentRelation;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentRelationMapper;
import com.superprogrammer.knowledge.mapper.RagRetrievalQueryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * C1 step6.5 计划生成（WP1 Step2，规格 §3.2）：四类型触发语义、1 跳硬限、去重、
 * 可见性静默丢弃、MUST 覆盖 MAY、perDoc 截断、无边零回归。
 */
@ExtendWith(MockitoExtension.class)
class RelationGraphPostProcessorTest {

    private static final Long KB = 1L;
    private static final Long DOC_A = 11L;
    private static final Long DOC_B = 12L;
    private static final Long DOC_C = 13L;

    @Mock private KnowledgeDocumentRelationMapper relationMapper;
    @Mock private RagRetrievalQueryMapper queryMapper;

    @InjectMocks private RelationGraphPostProcessor processor;

    private static KnowledgeDocumentRelation edge(Long docId, Long relatedDocId, String type) {
        KnowledgeDocumentRelation e = new KnowledgeDocumentRelation();
        e.setKbId(KB);
        e.setDocId(docId);
        e.setRelatedDocId(relatedDocId);
        e.setRelationType(type);
        return e;
    }

    private static RagQueryRow.RelationDocRow l2(Long docId, Long nodeId) {
        RagQueryRow.RelationDocRow r = new RagQueryRow.RelationDocRow();
        r.setDocumentId(docId);
        r.setDocTitle("文档" + docId);
        r.setNodeId(nodeId);
        r.setParentId(docId * 100);
        r.setTitle("节点" + nodeId);
        r.setContent("内容" + nodeId);
        r.setContentHash("hash" + nodeId);
        return r;
    }

    /** 先备好边 + L2 行 stub，再跑 planExpansion（allDocs=true）。 */
    private RelationGraphPostProcessor.ExpansionPlan expand(
            List<KnowledgeDocumentRelation> edges, Set<Long> hitDocs,
            List<RagQueryRow.RelationDocRow> l2Rows) {
        when(relationMapper.selectList(any())).thenReturn(edges);
        when(queryMapper.fetchRelationDocL2(anyLong(), anyList())).thenReturn(l2Rows);
        return processor.planExpansion(KB, hitDocs, true, List.of(), 5);
    }

    @Test
    void mustCite_outEdge_bringOut() {
        RelationGraphPostProcessor.ExpansionPlan plan = expand(
                List.of(edge(DOC_A, DOC_B, "MUST_CITE")), Set.of(DOC_A),
                List.of(l2(DOC_B, 21L), l2(DOC_B, 22L)));

        assertEquals(2, plan.mustNodes().size());
        assertEquals(21L, plan.mustNodes().get(0).nodeId());
        assertTrue(plan.mayNodes().isEmpty());
        assertTrue(plan.relatedDocs().isEmpty());
        assertEquals(1, plan.edgesScanned());
        assertEquals(0, plan.droppedByPermission());
    }

    /** 入边 MUST_BE_CITED(X→A)：命中 A ⇒ X 强制带出（等价反向读）。 */
    @Test
    void mustBeCited_inEdge_reverseRead() {
        RelationGraphPostProcessor.ExpansionPlan plan = expand(
                List.of(edge(DOC_B, DOC_A, "MUST_BE_CITED")), Set.of(DOC_A),
                List.of(l2(DOC_B, 21L)));

        assertEquals(1, plan.mustNodes().size());
        assertEquals(DOC_B, plan.mustNodes().get(0).documentId());
    }

    /** 1 跳硬限：A→B→C，命中 A 只带 B 不带 C（B ∉ H，B→C 不触发）。 */
    @Test
    void hop1Limit_noTransitive() {
        RelationGraphPostProcessor.ExpansionPlan plan = expand(
                List.of(edge(DOC_A, DOC_B, "MUST_CITE"), edge(DOC_B, DOC_C, "MUST_CITE")),
                Set.of(DOC_A),
                List.of(l2(DOC_B, 21L)));

        assertEquals(1, plan.mustNodes().size());
        assertEquals(DOC_B, plan.mustNodes().get(0).documentId());
        assertEquals(2, plan.edgesScanned());
    }

    /** 去重：目标已在 H → 跳过（A⇄B 双向边只表现为一次注入，天然防环）。 */
    @Test
    void dedup_targetAlreadyInH_skipped() {
        when(relationMapper.selectList(any()))
                .thenReturn(List.of(edge(DOC_A, DOC_B, "MUST_CITE"), edge(DOC_B, DOC_A, "MUST_CITE")));

        RelationGraphPostProcessor.ExpansionPlan plan =
                processor.planExpansion(KB, Set.of(DOC_A, DOC_B), true, List.of(), 5);

        assertTrue(plan.isEmpty());
        verifyNoInteractions(queryMapper);
    }

    /** 可见性静默丢弃：目标 ∉ 可见集且非 allDocs → 丢弃计数，不抛错（防权限探测侧信道）。 */
    @Test
    void invisibleTarget_silentDrop() {
        when(relationMapper.selectList(any()))
                .thenReturn(List.of(edge(DOC_A, DOC_B, "MUST_CITE")));

        RelationGraphPostProcessor.ExpansionPlan plan = processor.planExpansion(
                KB, Set.of(DOC_A), false, List.of(DOC_A), 5);

        assertTrue(plan.mustNodes().isEmpty());
        assertEquals(1, plan.droppedByPermission());
    }

    /** 可见集受限时目标在集内 → 正常带出（同库单判定复用 vs）。 */
    @Test
    void visibleTarget_bringOut() {
        when(relationMapper.selectList(any()))
                .thenReturn(List.of(edge(DOC_A, DOC_B, "MUST_CITE")));
        when(queryMapper.fetchRelationDocL2(anyLong(), anyList()))
                .thenReturn(List.of(l2(DOC_B, 21L)));

        RelationGraphPostProcessor.ExpansionPlan plan = processor.planExpansion(
                KB, Set.of(DOC_A), false, List.of(DOC_A, DOC_B), 5);

        assertEquals(1, plan.mustNodes().size());
        assertEquals(0, plan.droppedByPermission());
    }

    @Test
    void mayCite_outEdge_mayNodes() {
        RelationGraphPostProcessor.ExpansionPlan plan = expand(
                List.of(edge(DOC_A, DOC_B, "MAY_CITE")), Set.of(DOC_A),
                List.of(l2(DOC_B, 21L)));

        assertTrue(plan.mustNodes().isEmpty());
        assertEquals(1, plan.mayNodes().size());
    }

    /** 入边 MAY_BE_CITED(X→A)：命中 A ⇒ X 仅进「相关文档」区，不进证据。 */
    @Test
    void mayBeCited_inEdge_relatedDocsOnly() {
        when(relationMapper.selectList(any()))
                .thenReturn(List.of(edge(DOC_B, DOC_A, "MAY_BE_CITED")));
        RagQueryRow.DocTitleRow title = new RagQueryRow.DocTitleRow();
        title.setDocumentId(DOC_B);
        title.setTitle("免责条款");
        when(queryMapper.listValidDocTitles(anyLong(), anyList())).thenReturn(List.of(title));

        RelationGraphPostProcessor.ExpansionPlan plan =
                processor.planExpansion(KB, Set.of(DOC_A), true, List.of(), 5);

        assertTrue(plan.mustNodes().isEmpty());
        assertTrue(plan.mayNodes().isEmpty());
        assertEquals(1, plan.relatedDocs().size());
        assertEquals("免责条款", plan.relatedDocs().get(0).title());
        assertEquals("MAY_BE_CITED", plan.relatedDocs().get(0).relationType());
    }

    /** 同目标同时挂 MUST/MAY → MUST 语义覆盖（强语义优先）。 */
    @Test
    void mustOverridesMay_sameTarget() {
        RelationGraphPostProcessor.ExpansionPlan plan = expand(
                List.of(edge(DOC_A, DOC_B, "MUST_CITE"), edge(DOC_A, DOC_B, "MAY_CITE")),
                Set.of(DOC_A),
                List.of(l2(DOC_B, 21L)));

        assertEquals(1, plan.mustNodes().size());
        assertTrue(plan.mayNodes().isEmpty());
    }

    /** perDocL2Cap：带出文档 L2 节点超限截断（SQL 按 document_id,id 有序 → 取前 N 稳定）。 */
    @Test
    void perDocCap_truncates() {
        when(relationMapper.selectList(any()))
                .thenReturn(List.of(edge(DOC_A, DOC_B, "MUST_CITE")));
        when(queryMapper.fetchRelationDocL2(anyLong(), anyList()))
                .thenReturn(List.of(l2(DOC_B, 21L), l2(DOC_B, 22L), l2(DOC_B, 23L)));

        RelationGraphPostProcessor.ExpansionPlan plan = processor.planExpansion(
                KB, Set.of(DOC_A), true, List.of(), 2);

        assertEquals(2, plan.mustNodes().size());
        assertEquals(List.of(21L, 22L),
                plan.mustNodes().stream().map(RelationGraphPostProcessor.RelationNode::nodeId).toList());
    }

    /** 无边 → 空计划（零回归路径：调用方 merge 原样返回原始 topK）。 */
    @Test
    void noEdges_emptyPlan() {
        when(relationMapper.selectList(any())).thenReturn(List.of());

        RelationGraphPostProcessor.ExpansionPlan plan =
                processor.planExpansion(KB, Set.of(DOC_A), true, List.of(), 5);

        assertTrue(plan.isEmpty());
        assertEquals(0, plan.edgesScanned());
    }

    /** H 空 → 不查边（早退）。 */
    @Test
    void emptyHitDocs_noQuery() {
        RelationGraphPostProcessor.ExpansionPlan plan =
                processor.planExpansion(KB, Set.of(), true, List.of(), 5);

        assertTrue(plan.isEmpty());
        verifyNoInteractions(relationMapper);
    }
}
