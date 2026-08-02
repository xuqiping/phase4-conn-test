package com.superprogrammer.knowledge.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.knowledge.dto.KnowledgeNodeVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

/**
 * KnowledgeNodeService 测（必做收口 #4）：canRead 门 + doc 存在性 + flat 映射。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeNodeServiceTest {

    @Mock private KnowledgeNodeMapper nodeMapper;
    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private KnowledgeBaseService knowledgeBaseService;

    @InjectMocks private KnowledgeNodeService service;

    @Test
    void listByDocument_returnsNodesWhenCanRead() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(2L);
        doc.setKbId(1L);
        when(documentMapper.selectById(2L)).thenReturn(doc);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        when(knowledgeBaseService.ensure(1L)).thenReturn(kb);
        // KnowledgeBase 是 @Data → 用 same() 恒等避 eq() 跨实例串台
        when(knowledgeBaseService.canRead(same(kb), anyLong(), anyBoolean())).thenReturn(true);
        KnowledgeNode l0 = node(10L, null, "L0");
        KnowledgeNode l2 = node(11L, 10L, "L2");
        when(nodeMapper.selectList(any())).thenReturn(List.of(l0, l2));

        List<KnowledgeNodeVO> vos = service.listByDocument(2L, 7L, false);

        assertEquals(2, vos.size());
        assertEquals(10L, vos.get(0).getId());
        assertNull(vos.get(0).getParentId());
        assertEquals("L0", vos.get(0).getLevel());
        assertEquals(10L, vos.get(1).getParentId());   // L2.parentId 指向 L0
        assertEquals("L2", vos.get(1).getLevel());
    }

    @Test
    void listByDocument_forbiddenWhenNoRead() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKbId(1L);
        when(documentMapper.selectById(2L)).thenReturn(doc);
        when(knowledgeBaseService.ensure(1L)).thenReturn(new KnowledgeBase());
        when(knowledgeBaseService.canRead(any(), anyLong(), anyBoolean())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.listByDocument(2L, 7L, false));
        assertTrue(ex.getMessage().contains("无权"));
        verify(nodeMapper, never()).selectList(any());
    }

    @Test
    void listByDocument_docNotFoundThrows() {
        when(documentMapper.selectById(99L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.listByDocument(99L, 7L, false));
        verifyNoInteractions(nodeMapper);
    }

    private KnowledgeNode node(long id, Long parentId, String level) {
        KnowledgeNode n = new KnowledgeNode();
        n.setId(id);
        n.setParentId(parentId);
        n.setLevel(level);
        n.setNodeType("SECTION");
        n.setTitle(level + "-title");
        n.setDocumentId(2L);
        return n;
    }
}
