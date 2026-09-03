package com.superprogrammer.knowledge.relation;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.KnowledgeRelationRequest;
import com.superprogrammer.knowledge.dto.KnowledgeRelationSuggestionVO;
import com.superprogrammer.knowledge.dto.RelationSuggestionAdoptRequest;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeDocumentRelationSuggestion;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentRelationSuggestionMapper;
import com.superprogrammer.knowledge.service.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * C1 关联建议裁决入口（WP1 Step3，规格 §3.3）：采纳→委托建边（方向由 fromDocId 定）+
 * 建议 ADOPTED；边已存在=目标态达成同样收口；忽略置 IGNORED；canManage 三路 403；
 * 已处理建议拒重复操作；列表按共召回次数降序+悬挂过滤。
 */
@ExtendWith(MockitoExtension.class)
class RelationSuggestionServiceTest {

    private static final Long KB_ID = 1L;
    private static final Long DOC_A = 11L;
    private static final Long DOC_B = 12L;
    private static final Long MANAGER = 9L;
    private static final Long OUTSIDER = 20L;

    @Mock private KnowledgeDocumentRelationSuggestionMapper suggestionMapper;
    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private DocumentRelationService documentRelationService;

    @InjectMocks private RelationSuggestionService service;

    private KnowledgeBase kb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setCreatedBy(7L);
        return kb;
    }

    private KnowledgeDocumentRelationSuggestion pending() {
        KnowledgeDocumentRelationSuggestion s = new KnowledgeDocumentRelationSuggestion();
        s.setId(77L);
        s.setKbId(KB_ID);
        s.setDocIdA(DOC_A);
        s.setDocIdB(DOC_B);
        s.setCoRecallCount(5);
        s.setSampleQueryHash("abc");
        s.setStatus(KnowledgeDocumentRelationSuggestion.STATUS_PENDING);
        return s;
    }

    private RelationSuggestionAdoptRequest adoptReq(Long fromDocId, String type) {
        RelationSuggestionAdoptRequest r = new RelationSuggestionAdoptRequest();
        r.setFromDocId(fromDocId);
        r.setRelationType(type);
        r.setNote("采纳备注");
        return r;
    }

    private void stubCanManage() {
        when(knowledgeBaseService.ensure(KB_ID)).thenReturn(kb());
        when(knowledgeBaseService.canManage(any(KnowledgeBase.class), eq(MANAGER), anyBoolean()))
                .thenReturn(true);
    }

    @Test
    void adopt_createsEdgeWithChosenDirection_andMarksAdopted() {
        KnowledgeDocumentRelationSuggestion s = pending();
        when(suggestionMapper.selectById(77L)).thenReturn(s);
        stubCanManage();

        service.adopt(77L, adoptReq(DOC_B, "MUST_CITE"), MANAGER, false);   // B→A 主动方

        // 建边方向=fromDocId→另一端，全参数透传
        ArgumentCaptor<KnowledgeRelationRequest> cap =
                ArgumentCaptor.forClass(KnowledgeRelationRequest.class);
        verify(documentRelationService).create(cap.capture(), eq(MANAGER), eq(false));
        KnowledgeRelationRequest edge = cap.getValue();
        assertEquals(KB_ID, edge.getKbId());
        assertEquals(DOC_B, edge.getDocId());
        assertEquals(DOC_A, edge.getRelatedDocId());
        assertEquals("MUST_CITE", edge.getRelationType());
        assertEquals("采纳备注", edge.getNote());

        ArgumentCaptor<KnowledgeDocumentRelationSuggestion> markCap =
                ArgumentCaptor.forClass(KnowledgeDocumentRelationSuggestion.class);
        verify(suggestionMapper).updateById(markCap.capture());
        assertEquals(KnowledgeDocumentRelationSuggestion.STATUS_ADOPTED, markCap.getValue().getStatus());
    }

    @Test
    void adopt_edgeAlreadyExists_stillAdopted() {
        when(suggestionMapper.selectById(77L)).thenReturn(pending());
        stubCanManage();
        when(documentRelationService.create(any(), eq(MANAGER), anyBoolean()))
                .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "关联已存在"));

        assertDoesNotThrow(() ->
                service.adopt(77L, adoptReq(DOC_A, "MAY_CITE"), MANAGER, false));

        ArgumentCaptor<KnowledgeDocumentRelationSuggestion> cap =
                ArgumentCaptor.forClass(KnowledgeDocumentRelationSuggestion.class);
        verify(suggestionMapper).updateById(cap.capture());
        assertEquals(KnowledgeDocumentRelationSuggestion.STATUS_ADOPTED, cap.getValue().getStatus());
    }

    @Test
    void adopt_otherCreateFailures_propagate_suggestionUntouched() {
        when(suggestionMapper.selectById(77L)).thenReturn(pending());
        stubCanManage();
        when(documentRelationService.create(any(), eq(MANAGER), anyBoolean()))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "文档不存在：id=11"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.adopt(77L, adoptReq(DOC_A, "MAY_CITE"), MANAGER, false));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        verify(suggestionMapper, never()).updateById(any());
    }

    @Test
    void adopt_fromDocIdOutsidePair_rejected() {
        when(suggestionMapper.selectById(77L)).thenReturn(pending());
        stubCanManage();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.adopt(77L, adoptReq(999L, "MAY_CITE"), MANAGER, false));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(documentRelationService, never()).create(any(), any(), anyBoolean());
    }

    @Test
    void adopt_noCanManage_forbidden_noEdgeNoMark() {
        when(suggestionMapper.selectById(77L)).thenReturn(pending());
        when(knowledgeBaseService.ensure(KB_ID)).thenReturn(kb());
        when(knowledgeBaseService.canManage(any(KnowledgeBase.class), eq(OUTSIDER), anyBoolean()))
                .thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.adopt(77L, adoptReq(DOC_A, "MAY_CITE"), OUTSIDER, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(documentRelationService, never()).create(any(), any(), anyBoolean());
        verify(suggestionMapper, never()).updateById(any());
    }

    @Test
    void ignore_marksIgnored() {
        when(suggestionMapper.selectById(77L)).thenReturn(pending());
        stubCanManage();

        service.ignore(77L, MANAGER, false);

        ArgumentCaptor<KnowledgeDocumentRelationSuggestion> cap =
                ArgumentCaptor.forClass(KnowledgeDocumentRelationSuggestion.class);
        verify(suggestionMapper).updateById(cap.capture());
        assertEquals(KnowledgeDocumentRelationSuggestion.STATUS_IGNORED, cap.getValue().getStatus());
        verify(documentRelationService, never()).create(any(), any(), anyBoolean());
    }

    @Test
    void processedSuggestion_rejectsReOperation() {
        KnowledgeDocumentRelationSuggestion s = pending();
        s.setStatus(KnowledgeDocumentRelationSuggestion.STATUS_ADOPTED);
        when(suggestionMapper.selectById(77L)).thenReturn(s);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.ignore(77L, MANAGER, false));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void listByKb_ordersByCoRecall_andFiltersDangling() {
        when(knowledgeBaseService.ensure(KB_ID)).thenReturn(kb());
        when(knowledgeBaseService.canManage(any(KnowledgeBase.class), eq(MANAGER), anyBoolean()))
                .thenReturn(true);
        KnowledgeDocumentRelationSuggestion s1 = pending();          // count 5，两端健在
        KnowledgeDocumentRelationSuggestion s2 = pending();
        s2.setId(78L);
        s2.setDocIdA(30L);
        s2.setDocIdB(31L);
        s2.setCoRecallCount(8);
        when(suggestionMapper.selectList(any())).thenReturn(List.of(s2, s1));   // mapper 已按 count 降序回
        KnowledgeDocument d11 = new KnowledgeDocument();
        d11.setId(11L);
        d11.setTitle("差旅制度");
        KnowledgeDocument d12 = new KnowledgeDocument();
        d12.setId(12L);
        d12.setTitle("术语表");
        KnowledgeDocument d30 = new KnowledgeDocument();
        d30.setId(30L);
        d30.setTitle("健在文档");
        // 31 缺席 = 已删 → s2 悬挂过滤
        when(documentMapper.selectBatchIds(any())).thenReturn(List.of(d11, d12, d30));

        List<KnowledgeRelationSuggestionVO> result = service.listByKb(KB_ID, MANAGER, false);

        assertEquals(1, result.size());
        assertEquals(77L, result.get(0).getId());
        assertEquals(5, result.get(0).getCoRecallCount());
    }

    @Test
    void listByKb_noCanManage_forbidden() {
        when(knowledgeBaseService.ensure(KB_ID)).thenReturn(kb());
        when(knowledgeBaseService.canManage(any(KnowledgeBase.class), eq(OUTSIDER), anyBoolean()))
                .thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.listByKb(KB_ID, OUTSIDER, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(suggestionMapper, never()).selectList(any());
    }
}
