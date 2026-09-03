package com.superprogrammer.knowledge.relation;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.KnowledgeRelationRequest;
import com.superprogrammer.knowledge.dto.KnowledgeRelationVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeDocumentRelation;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentRelationMapper;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * C1 边管理（WP1 Step1，规格 §3.1）：四类型建边/双向读、自环拒、跨库拒、语义等价反向拒、
 * 同向重复拒、canManage 403、悬挂边过滤。
 */
@ExtendWith(MockitoExtension.class)
class DocumentRelationServiceTest {

    private static final Long KB_ID = 1L;
    private static final Long DOC_A = 11L;
    private static final Long DOC_B = 12L;
    private static final Long OWNER = 7L;
    private static final Long MANAGER = 9L;

    @Mock private KnowledgeDocumentRelationMapper relationMapper;
    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private KnowledgeBaseService knowledgeBaseService;

    @InjectMocks private DocumentRelationService service;

    private KnowledgeBase kb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setName("test-kb");
        kb.setCreatedBy(OWNER);
        return kb;
    }

    private KnowledgeDocument doc(Long id, Long kbId, String title) {
        KnowledgeDocument d = new KnowledgeDocument();
        d.setId(id);
        d.setKbId(kbId);
        d.setTitle(title);
        d.setStatus("INDEXED");
        return d;
    }

    private KnowledgeRelationRequest req(String type) {
        KnowledgeRelationRequest r = new KnowledgeRelationRequest();
        r.setKbId(KB_ID);
        r.setDocId(DOC_A);
        r.setRelatedDocId(DOC_B);
        r.setRelationType(type);
        r.setNote("测试备注");
        return r;
    }

    private void stubManagerCanManage() {
        when(knowledgeBaseService.ensure(KB_ID)).thenReturn(kb());
        when(knowledgeBaseService.canManage(any(KnowledgeBase.class), eq(MANAGER), anyBoolean())).thenReturn(true);
    }

    private void stubDocs() {
        when(documentMapper.selectById(DOC_A)).thenReturn(doc(DOC_A, KB_ID, "差旅制度"));
        when(documentMapper.selectById(DOC_B)).thenReturn(doc(DOC_B, KB_ID, "术语表"));
    }

    @Test
    void create_fourTypes_allInsertWithOutDirection() {
        List<String> types = List.of("MUST_CITE", "MAY_CITE", "MUST_BE_CITED", "MAY_BE_CITED");
        stubManagerCanManage();
        stubDocs();
        when(relationMapper.selectCount(any())).thenReturn(0L);

        List<String> insertedTypes = new java.util.ArrayList<>();
        for (String type : types) {
            KnowledgeRelationVO vo = service.create(req(type), MANAGER, false);
            assertEquals("OUT", vo.getDirection());
            assertEquals(DOC_B, vo.getOtherDocId());
            assertEquals("术语表", vo.getOtherDocTitle());
            insertedTypes.add(type);
        }
        ArgumentCaptor<KnowledgeDocumentRelation> captor =
                ArgumentCaptor.forClass(KnowledgeDocumentRelation.class);
        verify(relationMapper, times(4)).insert(captor.capture());
        List<String> captured = captor.getAllValues().stream()
                .map(KnowledgeDocumentRelation::getRelationType).toList();
        assertEquals(insertedTypes, captured);
        captor.getAllValues().forEach(e -> assertEquals(KB_ID, e.getKbId()));
    }

    @Test
    void create_selfLoop_rejected() {
        stubManagerCanManage();
        KnowledgeRelationRequest r = req("MUST_CITE");
        r.setRelatedDocId(DOC_A);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(r, MANAGER, false));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void create_crossKb_rejected() {
        stubManagerCanManage();
        when(documentMapper.selectById(DOC_A)).thenReturn(doc(DOC_A, KB_ID, "差旅制度"));
        when(documentMapper.selectById(DOC_B)).thenReturn(doc(DOC_B, 999L, "别的库的文档"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req("MUST_CITE"), MANAGER, false));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(relationMapper, never()).insert(any());
    }

    @Test
    void create_docMissing_notFound() {
        stubManagerCanManage();
        when(documentMapper.selectById(DOC_A)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req("MUST_CITE"), MANAGER, false));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void create_duplicateDirect_rejected() {
        stubManagerCanManage();
        stubDocs();
        when(relationMapper.selectCount(any())).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req("MUST_CITE"), MANAGER, false));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(relationMapper, never()).insert(any());
    }

    /** 语义等价：已存在 MUST_CITE(B→A) 时建 MUST_BE_CITED(A→B) 被拒（唯一约束拦不住方向颠倒）。 */
    @Test
    void create_semanticEquivalentReverse_rejected() {
        stubManagerCanManage();
        stubDocs();
        // 第一次 selectCount（同向）=0，第二次（反向等价）=1
        when(relationMapper.selectCount(any())).thenReturn(0L).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req("MUST_BE_CITED"), MANAGER, false));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("等价"));
        verify(relationMapper, never()).insert(any());
    }

    @Test
    void create_withoutManage_forbidden() {
        when(knowledgeBaseService.ensure(KB_ID)).thenReturn(kb());
        when(knowledgeBaseService.canManage(any(KnowledgeBase.class), eq(8L), anyBoolean())).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req("MUST_CITE"), 8L, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void delete_withoutManage_forbidden() {
        KnowledgeDocumentRelation edge = new KnowledgeDocumentRelation();
        edge.setId(5L);
        edge.setKbId(KB_ID);
        edge.setDocId(DOC_A);
        edge.setRelatedDocId(DOC_B);
        edge.setRelationType("MUST_CITE");
        when(relationMapper.selectById(5L)).thenReturn(edge);
        when(knowledgeBaseService.ensure(KB_ID)).thenReturn(kb());
        when(knowledgeBaseService.canManage(any(KnowledgeBase.class), eq(8L), anyBoolean())).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(5L, 8L, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void listByDoc_outAndInMerged_danglingFiltered() {
        when(knowledgeBaseService.ensure(KB_ID)).thenReturn(kb());
        when(knowledgeBaseService.canRead(any(KnowledgeBase.class), eq(MANAGER), anyBoolean())).thenReturn(true);
        when(documentMapper.selectById(DOC_A)).thenReturn(doc(DOC_A, KB_ID, "差旅制度"));

        KnowledgeDocumentRelation out1 = new KnowledgeDocumentRelation();
        out1.setId(1L);
        out1.setKbId(KB_ID);
        out1.setDocId(DOC_A);
        out1.setRelatedDocId(DOC_B);
        out1.setRelationType("MUST_CITE");
        KnowledgeDocumentRelation in1 = new KnowledgeDocumentRelation();
        in1.setId(2L);
        in1.setKbId(KB_ID);
        in1.setDocId(13L);
        in1.setRelatedDocId(DOC_A);
        in1.setRelationType("MAY_BE_CITED");
        KnowledgeDocumentRelation dangling = new KnowledgeDocumentRelation();
        dangling.setId(3L);
        dangling.setKbId(KB_ID);
        dangling.setDocId(99L);
        dangling.setRelatedDocId(DOC_A);
        dangling.setRelationType("MUST_BE_CITED");

        when(relationMapper.selectList(any())).thenReturn(List.of(out1)).thenReturn(List.of(in1, dangling));
        // 批量取标题：99 已删不返回 → 悬挂过滤；12/13 返回
        when(documentMapper.selectBatchIds(any()))
                .thenReturn(List.of(doc(DOC_B, KB_ID, "术语表"), doc(13L, KB_ID, "免责条款")));

        List<KnowledgeRelationVO> vos = service.listByDoc(KB_ID, DOC_A, MANAGER, false);

        assertEquals(2, vos.size());
        assertTrue(vos.stream().anyMatch(v -> v.getDirection().equals("OUT")
                && "MUST_CITE".equals(v.getRelationType()) && v.getOtherDocId().equals(DOC_B)));
        assertTrue(vos.stream().anyMatch(v -> v.getDirection().equals("IN")
                && "MAY_BE_CITED".equals(v.getRelationType()) && v.getOtherDocId().equals(13L)));
        // 悬挂边（另一端 99 已删）不出现
        assertFalse(vos.stream().anyMatch(v -> v.getOtherDocId().equals(99L)));
    }

    @Test
    void delete_byManager_hardDeletes() {
        KnowledgeDocumentRelation edge = new KnowledgeDocumentRelation();
        edge.setId(5L);
        edge.setKbId(KB_ID);
        when(relationMapper.selectById(5L)).thenReturn(edge);
        when(knowledgeBaseService.ensure(KB_ID)).thenReturn(kb());
        when(knowledgeBaseService.canManage(any(KnowledgeBase.class), eq(MANAGER), anyBoolean())).thenReturn(true);

        service.delete(5L, MANAGER, false);

        verify(relationMapper).deleteById(5L);
    }
}
