package com.superprogrammer.knowledge.relation;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * C1 关联建议 worker（WP1 Step3，规格 §3.3）：共召回≥阈值→建议生成 / 门槛不足不生成 /
 * 跨库对丢弃 / 已建边跳过 / ADOPTED 不重提 / PENDING 续算 / 开关关闭直通。
 */
@ExtendWith(MockitoExtension.class)
class RelationSuggestionWorkerTest {

    private static final Long KB_ID = 1L;
    private static final Long DOC_A = 11L;   // < DOC_B，天然 a<b 规范
    private static final Long DOC_B = 12L;

    @Mock private RagRetrievalLogMapper logMapper;
    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private KnowledgeDocumentRelationMapper relationMapper;
    @Mock private KnowledgeDocumentRelationSuggestionMapper suggestionMapper;

    private RagRecallProperties props;
    private RelationSuggestionWorker worker;

    @BeforeEach
    void setUp() {
        props = new RagRecallProperties();
        worker = new RelationSuggestionWorker(logMapper, documentMapper, relationMapper,
                suggestionMapper, props, new ObjectMapper());
    }

    private RagRetrievalLog trace(long id, String evidenceJson) {
        RagRetrievalLog l = new RagRetrievalLog();
        l.setId(id);
        l.setQuery("差旅报销流程");
        l.setEvidenceL2(evidenceJson);
        l.setCreatedAt(OffsetDateTime.now());
        return l;
    }

    /** 一条 trace 证据含 A、B 两文档。 */
    private String evidenceAB() {
        return "[{\"documentId\":" + DOC_A + "},{\"documentId\":" + DOC_B + "}]";
    }

    private KnowledgeDocument doc(Long id, Long kbId) {
        KnowledgeDocument d = new KnowledgeDocument();
        d.setId(id);
        d.setKbId(kbId);
        return d;
    }

    private void stubNoEdgesNoSuggestions() {
        when(relationMapper.selectList(any())).thenReturn(List.of());
        when(suggestionMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void coRecallMeetsThreshold_generatesPendingSuggestion() {
        when(logMapper.selectList(any())).thenReturn(List.of(
                trace(1, evidenceAB()), trace(2, evidenceAB()), trace(3, evidenceAB()),
                trace(4, null)));   // 证据空行照扫不产对
        when(documentMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(doc(DOC_A, KB_ID), doc(DOC_B, KB_ID)));
        stubNoEdgesNoSuggestions();

        int created = worker.run();

        assertEquals(1, created);
        ArgumentCaptor<KnowledgeDocumentRelationSuggestion> cap =
                ArgumentCaptor.forClass(KnowledgeDocumentRelationSuggestion.class);
        verify(suggestionMapper, times(1)).insert(cap.capture());
        KnowledgeDocumentRelationSuggestion row = cap.getValue();
        assertEquals(KB_ID, row.getKbId());
        assertEquals(DOC_A, row.getDocIdA());   // a<b 规范
        assertEquals(DOC_B, row.getDocIdB());
        assertEquals(3, row.getCoRecallCount());
        assertEquals(KnowledgeDocumentRelationSuggestion.STATUS_PENDING, row.getStatus());
        assertNotNull(row.getSampleQueryHash());
        assertNotNull(row.getLastSeenAt());
    }

    @Test
    void belowThreshold_noSuggestion() {
        when(logMapper.selectList(any())).thenReturn(List.of(trace(1, evidenceAB()), trace(2, evidenceAB())));
        when(documentMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(doc(DOC_A, KB_ID), doc(DOC_B, KB_ID)));
        stubNoEdgesNoSuggestions();

        int created = worker.run();

        assertEquals(0, created);
        verify(suggestionMapper, never()).insert(any());
    }

    @Test
    void crossKbPair_dropped() {
        when(logMapper.selectList(any())).thenReturn(List.of(
                trace(1, evidenceAB()), trace(2, evidenceAB()), trace(3, evidenceAB())));
        when(documentMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(doc(DOC_A, KB_ID), doc(DOC_B, 999L)));   // 不同库
        stubNoEdgesNoSuggestions();

        int created = worker.run();

        assertEquals(0, created);
        verify(suggestionMapper, never()).insert(any());
    }

    @Test
    void deletedDocEnd_pairDropped() {
        when(logMapper.selectList(any())).thenReturn(List.of(
                trace(1, evidenceAB()), trace(2, evidenceAB()), trace(3, evidenceAB())));
        when(documentMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(doc(DOC_A, KB_ID)));   // DOC_B 已逻辑删，批查缺席
        stubNoEdgesNoSuggestions();

        assertEquals(0, worker.run());
        verify(suggestionMapper, never()).insert(any());
    }

    @Test
    void existingEdge_pairSkipped() {
        when(logMapper.selectList(any())).thenReturn(List.of(
                trace(1, evidenceAB()), trace(2, evidenceAB()), trace(3, evidenceAB())));
        when(documentMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(doc(DOC_A, KB_ID), doc(DOC_B, KB_ID)));
        KnowledgeDocumentRelation edge = new KnowledgeDocumentRelation();
        edge.setKbId(KB_ID);
        edge.setDocId(DOC_B);          // 反向边也算已建（双向任一）
        edge.setRelatedDocId(DOC_A);
        when(relationMapper.selectList(any())).thenReturn(List.of(edge));
        when(suggestionMapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, worker.run());
        verify(suggestionMapper, never()).insert(any());
    }

    @Test
    void adoptedSuggestion_notResuggested() {
        when(logMapper.selectList(any())).thenReturn(List.of(
                trace(1, evidenceAB()), trace(2, evidenceAB()), trace(3, evidenceAB())));
        when(documentMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(doc(DOC_A, KB_ID), doc(DOC_B, KB_ID)));
        when(relationMapper.selectList(any())).thenReturn(List.of());
        KnowledgeDocumentRelationSuggestion adopted =
                new KnowledgeDocumentRelationSuggestion();
        adopted.setKbId(KB_ID);
        adopted.setDocIdA(DOC_A);
        adopted.setDocIdB(DOC_B);
        adopted.setStatus(KnowledgeDocumentRelationSuggestion.STATUS_ADOPTED);
        when(suggestionMapper.selectList(any())).thenReturn(List.of(adopted));

        assertEquals(0, worker.run());
        verify(suggestionMapper, never()).insert(any());
        verify(suggestionMapper, never()).updateById(any());
    }

    @Test
    void pendingSuggestion_refreshedNotDuplicated() {
        when(logMapper.selectList(any())).thenReturn(List.of(
                trace(1, evidenceAB()), trace(2, evidenceAB()), trace(3, evidenceAB())));
        when(documentMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(doc(DOC_A, KB_ID), doc(DOC_B, KB_ID)));
        when(relationMapper.selectList(any())).thenReturn(List.of());
        KnowledgeDocumentRelationSuggestion pending =
                new KnowledgeDocumentRelationSuggestion();
        pending.setId(77L);
        pending.setKbId(KB_ID);
        pending.setDocIdA(DOC_A);
        pending.setDocIdB(DOC_B);
        pending.setCoRecallCount(2);
        pending.setStatus(KnowledgeDocumentRelationSuggestion.STATUS_PENDING);
        when(suggestionMapper.selectList(any())).thenReturn(List.of(pending));

        int created = worker.run();

        assertEquals(0, created);
        verify(suggestionMapper, never()).insert(any());
        ArgumentCaptor<KnowledgeDocumentRelationSuggestion> cap =
                ArgumentCaptor.forClass(KnowledgeDocumentRelationSuggestion.class);
        verify(suggestionMapper, times(1)).updateById(cap.capture());
        assertEquals(3, cap.getValue().getCoRecallCount());
        assertEquals(KnowledgeDocumentRelationSuggestion.STATUS_PENDING, cap.getValue().getStatus());
    }

    @Test
    void disabled_returnsZeroWithoutScan() {
        props.getRelation().setSuggestionEnabled(false);

        assertEquals(0, worker.run());
        verify(logMapper, never()).selectList(any());
    }

    @Test
    void malformedEvidence_skippedNotFatal() {
        when(logMapper.selectList(any())).thenReturn(List.of(
                trace(1, "not-json{{{"), trace(2, evidenceAB()), trace(3, evidenceAB())));
        when(documentMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(doc(DOC_A, KB_ID), doc(DOC_B, KB_ID)));
        stubNoEdgesNoSuggestions();
        // 脏行被跳 → 有效共现仅 2 次，低于门槛
        assertEquals(0, worker.run());
        verify(suggestionMapper, never()).insert(any());
    }
}
