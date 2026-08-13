package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentUpdateRequest;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.mapper.KnowledgeDocEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.knowledge.service.internal.ExcelSheetExtractor;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentMetadataGovernanceTest {

    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private KnowledgeNodeMapper nodeMapper;
    @Mock private KnowledgeEmbeddingMapper embeddingMapper;
    @Mock private KnowledgeDocEmbeddingMapper docEmbeddingMapper;
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private FileStorageService fileStorageService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ExcelSheetExtractor excelSheetExtractor;
    @Mock private SystemSettingService systemSettingService;
    @Mock private KnowledgeDocumentVersionService versionService;

    private KnowledgeDocumentService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeDocumentService(documentMapper, nodeMapper, embeddingMapper,
                docEmbeddingMapper, knowledgeBaseService, fileStorageService, eventPublisher,
                new ObjectMapper(), excelSheetExtractor, systemSettingService, versionService);
    }

    @Test
    void updateMetadata_rejectsInvalidEffectiveRange() {
        stubManageableDocument();
        KnowledgeDocumentUpdateRequest request = validRequest();
        request.setEffectiveAt(OffsetDateTime.parse("2026-08-14T00:00:00+08:00"));
        request.setExpiredAt(OffsetDateTime.parse("2026-08-13T23:59:59+08:00"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateMetadata(3L, request, 7L, true));

        assertTrue(ex.getMessage().contains("生效时间"));
        verify(documentMapper, never()).updateGovernance(any());
    }

    @Test
    void updateMetadata_rejectsUnknownAuthorityAndConfidentiality() {
        stubManageableDocument();
        KnowledgeDocumentUpdateRequest invalidAuthority = validRequest();
        invalidAuthority.setAuthorityLevel("KING");
        assertThrows(BusinessException.class, () -> service.updateMetadata(3L, invalidAuthority, 7L, true));

        KnowledgeDocumentUpdateRequest invalidConfidentiality = validRequest();
        invalidConfidentiality.setConfidentialityLevel("TOP_SECRET");
        assertThrows(BusinessException.class, () -> service.updateMetadata(3L, invalidConfidentiality, 7L, true));
    }

    @Test
    void updateMetadata_rejectsTooManyOrOversizedTags() {
        stubManageableDocument();
        KnowledgeDocumentUpdateRequest tooManyTags = validRequest();
        tooManyTags.setTags(java.util.stream.IntStream.range(0, 21).mapToObj(i -> "tag" + i).toList());
        assertThrows(BusinessException.class, () -> service.updateMetadata(3L, tooManyTags, 7L, true));

        KnowledgeDocumentUpdateRequest oversizedTag = validRequest();
        oversizedTag.setTags(List.of("x".repeat(65)));
        assertThrows(BusinessException.class, () -> service.updateMetadata(3L, oversizedTag, 7L, true));
    }

    @Test
    void updateMetadata_nonAdminCannotChangeConfidentiality() {
        KnowledgeDocument doc = stubManageableDocument();
        doc.setConfidentialityLevel("INTERNAL");
        KnowledgeDocumentUpdateRequest request = validRequest();
        request.setConfidentialityLevel("CONFIDENTIAL");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateMetadata(3L, request, 7L, false));

        assertTrue(ex.getMessage().contains("密级"));
        verify(documentMapper, never()).updateGovernance(any());
    }

    @Test
    void updateMetadata_normalizesTagsAndPersistsGovernanceFields() {
        stubManageableDocument();
        when(documentMapper.updateGovernance(any())).thenReturn(1);
        KnowledgeDocumentUpdateRequest request = validRequest();
        request.setTags(List.of(" 制度 ", "制度", "财务"));

        service.updateMetadata(3L, request, 7L, true);

        org.mockito.ArgumentCaptor<KnowledgeDocument> captor = org.mockito.ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documentMapper).updateGovernance(captor.capture());
        KnowledgeDocument saved = captor.getValue();
        assertEquals("OFFICIAL", saved.getAuthorityLevel());
        assertEquals("INTERNAL", saved.getConfidentialityLevel());
        assertEquals("[\"制度\",\"财务\"]", saved.getTags());
        assertEquals(7L, saved.getUpdatedBy());
    }

    private KnowledgeDocument stubManageableDocument() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(3L);
        doc.setKbId(1L);
        doc.setConfidentialityLevel("INTERNAL");
        when(documentMapper.selectById(3L)).thenReturn(doc);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        when(knowledgeBaseService.ensure(1L)).thenReturn(kb);
        lenient().when(knowledgeBaseService.canManage(kb, 7L, true)).thenReturn(true);
        lenient().when(knowledgeBaseService.canManage(kb, 7L, false)).thenReturn(true);
        return doc;
    }

    private KnowledgeDocumentUpdateRequest validRequest() {
        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setOwnerId(7L);
        request.setSourceType("UPLOAD");
        request.setSourceUri("manual://finance-policy");
        request.setSourceUpdatedAt(OffsetDateTime.parse("2026-08-12T10:00:00+08:00"));
        request.setAuthorityLevel("OFFICIAL");
        request.setConfidentialityLevel("INTERNAL");
        request.setEffectiveAt(OffsetDateTime.parse("2026-08-13T00:00:00+08:00"));
        request.setExpiredAt(OffsetDateTime.parse("2027-08-13T00:00:00+08:00"));
        request.setTags(List.of("制度"));
        return request;
    }
}
