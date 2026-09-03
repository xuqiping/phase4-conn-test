package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.event.VisibilityInvalidationEvent;
import com.superprogrammer.knowledge.mapper.KnowledgeDocEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeDocumentService.delete 测（修 Gap-1：doc 软删须同步软删 nodes + 硬删向量，否则孤儿泄漏）。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentServiceTest {

    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private KnowledgeNodeMapper nodeMapper;
    @Mock private KnowledgeEmbeddingMapper embeddingMapper;
    @Mock private KnowledgeDocEmbeddingMapper docEmbeddingMapper;
    @Mock private com.superprogrammer.knowledge.mapper.KnowledgeImageEmbeddingMapper imageEmbeddingMapper;
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private KnowledgeDocumentVersionService versionService;

    @InjectMocks private KnowledgeDocumentService service;

    @Test
    void delete_softDeletesDocNodesAndEmbeddings() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(3L);
        doc.setKbId(1L);
        when(documentMapper.selectById(3L)).thenReturn(doc);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        when(knowledgeBaseService.ensure(1L)).thenReturn(kb);
        when(knowledgeBaseService.canManage(same(kb), anyLong(), anyBoolean())).thenReturn(true);

        service.delete(3L, 1L, true);

        verify(documentMapper).deleteById(3L);
        verify(nodeMapper).delete(any(Wrapper.class));
        verify(embeddingMapper).deleteByDocument(3L);
        verify(docEmbeddingMapper).deleteByDocument(3L);   // Phase3：L1 文档向量同步清
        verify(imageEmbeddingMapper).deleteByDocument(3L); // WP5：图片文档向量同步清
        ArgumentCaptor<VisibilityInvalidationEvent> ev = ArgumentCaptor.forClass(VisibilityInvalidationEvent.class);
        verify(applicationEventPublisher).publishEvent(ev.capture());
        assertEquals(1L, ev.getValue().getKbId());
    }

    @Test
    void delete_forbiddenWhenNoManage() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKbId(1L);
        when(documentMapper.selectById(3L)).thenReturn(doc);
        when(knowledgeBaseService.ensure(1L)).thenReturn(new KnowledgeBase());
        when(knowledgeBaseService.canManage(any(KnowledgeBase.class), anyLong(), anyBoolean())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(3L, 7L, false));
        assertTrue(ex.getMessage().contains("删除文档"));
        // Gap-1 保证：权限拒时不触任何清理
        verify(documentMapper, never()).deleteById(anyLong());
        verify(nodeMapper, never()).delete(any());
        verify(embeddingMapper, never()).deleteByDocument(any());
        verify(docEmbeddingMapper, never()).deleteByDocument(any());
    }

    @Test
    void delete_notFoundThrows() {
        when(documentMapper.selectById(99L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.delete(99L, 1L, true));
        verifyNoCleanups();
    }

    private void verifyNoCleanups() {
        verify(documentMapper, never()).deleteById(anyLong());
        verify(nodeMapper, never()).delete(any());
        verify(embeddingMapper, never()).deleteByDocument(any());
        verify(docEmbeddingMapper, never()).deleteByDocument(any());
    }
}
