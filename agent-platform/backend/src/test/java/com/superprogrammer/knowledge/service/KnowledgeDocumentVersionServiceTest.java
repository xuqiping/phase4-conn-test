package com.superprogrammer.knowledge.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion;
import com.superprogrammer.knowledge.event.VisibilityInvalidationEvent;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentVersionServiceTest {

    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private KnowledgeDocumentVersionMapper versionMapper;
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private ApplicationEventPublisher eventPublisher;
    private KnowledgeDocumentVersionService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeDocumentVersionService(
                documentMapper, versionMapper, knowledgeBaseService, eventPublisher);
    }

    @Test
    void createInitialVersion_createsEffectiveV1AndMovesPointer() {
        KnowledgeDocument doc = document(7L, 3L, null);
        when(documentMapper.selectByIdForUpdate(7L)).thenReturn(doc);
        doAnswer(invocation -> {
            ((KnowledgeDocumentVersion) invocation.getArgument(0)).setId(11L);
            return 1;
        }).when(versionMapper).insert(any(KnowledgeDocumentVersion.class));
        when(documentMapper.moveCurrentVersion(7L, 11L, null, 9L)).thenReturn(1);
        KnowledgeDocumentVersion result = service.createInitialVersion(
                7L, "/api/files/f1", "hash-1", 9L, "首次上传");

        ArgumentCaptor<KnowledgeDocumentVersion> saved = ArgumentCaptor.forClass(KnowledgeDocumentVersion.class);
        verify(versionMapper).insert(saved.capture());
        assertEquals(1, saved.getValue().getVersionNo());
        assertEquals("EFFECTIVE", saved.getValue().getStatus());
        verify(documentMapper).moveCurrentVersion(7L, saved.getValue().getId(), null, 9L);
        assertSame(saved.getValue(), result);
    }

    @Test
    void createDraftVersion_isImmutableAndDoesNotMoveCurrentPointer() {
        KnowledgeDocument doc = document(7L, 3L, 11L);
        when(documentMapper.selectByIdForUpdate(7L)).thenReturn(doc);
        when(versionMapper.nextVersionNo(7L)).thenReturn(2);
        allowManage(doc);

        KnowledgeDocumentVersion result = service.createDraftVersion(
                7L, 11L, "/api/files/f2", "hash-2", "更新", 9L, false);

        assertEquals(2, result.getVersionNo());
        assertEquals(11L, result.getParentVersionId());
        assertEquals("DRAFT", result.getStatus());
        verify(documentMapper, never()).moveCurrentVersion(anyLong(), any(), any(), anyLong());
    }

    @Test
    void activateVersion_archivesOldEffectiveAndPublishesInvalidation() {
        KnowledgeDocument doc = document(7L, 3L, 11L);
        KnowledgeDocumentVersion target = version(22L, 7L, 2, "DRAFT");
        when(documentMapper.selectByIdForUpdate(7L)).thenReturn(doc);
        when(versionMapper.selectById(22L)).thenReturn(target);
        when(versionMapper.markEffective(22L, 9L)).thenReturn(1);
        when(documentMapper.moveCurrentVersion(7L, 22L, 11L, 9L)).thenReturn(1);
        allowManage(doc);

        service.activate(7L, 22L, 11L, 9L, false);

        verify(versionMapper).archiveEffective(7L, 22L);
        verify(versionMapper).markEffective(22L, 9L);
        verify(eventPublisher).publishEvent(isA(VisibilityInvalidationEvent.class));
    }

    @Test
    void activateVersion_rejectsStaleExpectedPointer() {
        KnowledgeDocument doc = document(7L, 3L, 33L);
        when(documentMapper.selectByIdForUpdate(7L)).thenReturn(doc);
        allowManage(doc);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.activate(7L, 22L, 11L, 9L, false));

        assertTrue(error.getMessage().contains("版本冲突"));
        verify(versionMapper, never()).markEffective(anyLong(), anyLong());
    }

    @Test
    void listHistory_requiresReadPermissionAndReturnsAllVersions() {
        KnowledgeDocument doc = document(7L, 3L, 11L);
        when(documentMapper.selectById(7L)).thenReturn(doc);
        KnowledgeBase kb = new KnowledgeBase(); kb.setId(3L);
        when(knowledgeBaseService.ensure(3L)).thenReturn(kb);
        when(knowledgeBaseService.canRead(kb, 9L, false)).thenReturn(true);
        when(versionMapper.listByDocument(7L)).thenReturn(List.of(version(11L, 7L, 1, "EFFECTIVE")));

        assertEquals(1, service.listHistory(7L, 9L, false).size());
    }

    @Test
    void revokeCurrentVersion_clearsPointerAndPublishesInvalidation() {
        KnowledgeDocument doc = document(7L, 3L, 22L);
        when(documentMapper.selectByIdForUpdate(7L)).thenReturn(doc);
        when(versionMapper.selectById(22L)).thenReturn(version(22L, 7L, 2, "EFFECTIVE"));
        when(versionMapper.revoke(22L, 9L)).thenReturn(1);
        when(documentMapper.moveCurrentVersion(7L, null, 22L, 9L)).thenReturn(1);
        allowManage(doc);

        service.revoke(7L, 22L, 9L, false);

        verify(eventPublisher).publishEvent(isA(VisibilityInvalidationEvent.class));
    }

    private void allowManage(KnowledgeDocument doc) {
        KnowledgeBase kb = new KnowledgeBase(); kb.setId(doc.getKbId());
        when(knowledgeBaseService.ensure(doc.getKbId())).thenReturn(kb);
        when(knowledgeBaseService.canManage(kb, 9L, false)).thenReturn(true);
    }

    private KnowledgeDocument document(Long id, Long kbId, Long currentVersionId) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(id); doc.setKbId(kbId); doc.setCurrentVersionId(currentVersionId);
        return doc;
    }

    private KnowledgeDocumentVersion version(Long id, Long documentId, int no, String status) {
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setId(id); version.setDocumentId(documentId); version.setVersionNo(no); version.setStatus(status);
        return version;
    }
}
