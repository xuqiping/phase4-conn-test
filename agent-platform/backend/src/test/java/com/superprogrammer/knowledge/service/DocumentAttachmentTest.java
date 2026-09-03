package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.mapper.KnowledgeDocEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.service.internal.ExcelSheetExtractor;
import com.superprogrammer.knowledge.service.internal.ExtractedDocument;
import com.superprogrammer.knowledge.service.internal.ParseArtifactService;
import com.superprogrammer.knowledge.service.internal.StructuredDocumentExtractor;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C2 ATTACHMENT 附件模式（WP1 Step5，规格 §4.2）：校验矩阵（缺描述拒/超长拒/MANUAL 同门）+
 * 单节点构造（描述+关键词）+ attachmentText 预提取（文本白名单直读截断/图片不预提取/失败降级 null）。
 */
@ExtendWith(MockitoExtension.class)
class DocumentAttachmentTest {

    private static final Long DOC_ID = 5L;
    private static final Long OWNER = 7L;

    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private FileStorageService fileStorageService;
    @Mock private LlmGateway llmGateway;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private KnowledgeNodeWriter knowledgeNodeWriter;
    @Mock private ExcelSheetExtractor excelExtractor;
    @Mock private SystemSettingService systemSettingService;
    @Mock private StructuredDocumentExtractor structuredDocumentExtractor;
    @Mock private ParseArtifactService parseArtifactService;
    @Mock private KnowledgeNodeMapper nodeMapper;
    @Mock private KnowledgeEmbeddingMapper embeddingMapper;
    @Mock private KnowledgeDocEmbeddingMapper docEmbeddingMapper;

    @InjectMocks private DocumentParserService parser;

    private KnowledgeDocument attachDoc(String fileSuffix, String keywords) {
        KnowledgeDocument d = new KnowledgeDocument();
        d.setId(DOC_ID);
        d.setKbId(1L);
        d.setTitle("架构图" + fileSuffix);
        d.setDocType("FILE");
        d.setFileRef("/api/files/f1" + fileSuffix);
        d.setCreatedBy(OWNER);
        String opts = keywords == null
                ? "{\"indexMode\":\"ATTACHMENT\",\"manualIndexText\":\"系统核心架构图，含部署拓扑\"}"
                : "{\"indexMode\":\"ATTACHMENT\",\"manualIndexText\":\"系统核心架构图，含部署拓扑\","
                        + "\"attachmentKeywords\":\"" + keywords + "\"}";
        d.setParseOptions(opts);
        return d;
    }

    private void stubFile(String content) {
        Resource res = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
        when(fileStorageService.load(eq("f1.txt"), eq(OWNER), anyBoolean())).thenReturn(res);
    }

    // ---- 校验矩阵（validateIndexText 包私有直测）----

    @Test
    void attachment_blankDescription_rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> KnowledgeDocumentService.validateIndexText("ATTACHMENT", " "));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("附件描述"));
    }

    @Test
    void attachment_overlongDescription_rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> KnowledgeDocumentService.validateIndexText("ATTACHMENT", "字".repeat(4001)));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void manual_blankText_sameGate() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> KnowledgeDocumentService.validateIndexText("MANUAL", null));
        assertTrue(ex.getMessage().contains("索引文本"));
    }

    @Test
    void auto_noGate() {
        assertDoesNotThrow(() -> KnowledgeDocumentService.validateIndexText("AUTO", null));
        assertDoesNotThrow(() -> KnowledgeDocumentService.validateIndexText("ATTACHMENT", "合规描述"));
    }

    // ---- 单节点构造 ----

    @Test
    void extractAttachment_singleSection_descPlusKeywords() {
        ExtractedDocument doc = parser.extractAttachment(attachDoc(".txt", "架构,部署"));

        assertEquals("ATTACHMENT", doc.getDocumentType());
        assertEquals("attachment", doc.getParserName());
        assertEquals(1, doc.getSections().size());
        String content = doc.getSections().get(0).getContent();
        assertTrue(content.contains("系统核心架构图，含部署拓扑"));
        assertTrue(content.contains("关键词：架构,部署"));
        assertEquals(doc.getSections().get(0).getTitle(), "架构图.txt");
    }

    @Test
    void extractAttachment_noKeywords_contentIsBareDescription() {
        ExtractedDocument doc = parser.extractAttachment(attachDoc(".txt", null));

        assertEquals(1, doc.getSections().size());
        assertFalse(doc.getSections().get(0).getContent().contains("关键词"));
    }

    @Test
    void extractAttachment_blankDescription_throws() {
        KnowledgeDocument d = attachDoc(".txt", null);
        d.setParseOptions("{\"indexMode\":\"ATTACHMENT\"}");
        assertThrows(RuntimeException.class, () -> parser.extractAttachment(d));
    }

    // ---- attachmentText 预提取 ----

    @Test
    void loadAttachmentText_plainText_truncatedAt8000() {
        stubFile("长".repeat(20000));
        String text = parser.loadAttachmentText(attachDoc(".txt", null));
        assertNotNull(text);
        assertEquals(8000, text.length());
    }

    @Test
    void loadAttachmentText_image_neverLoadsFile() {
        KnowledgeDocument d = attachDoc(".png", null);
        assertNull(parser.loadAttachmentText(d));
        verify(fileStorageService, never()).load(any(), any(), anyBoolean());
    }

    @Test
    void loadAttachmentText_tikaFailure_degradesToNull() {
        when(fileStorageService.load(eq("f1.pdf"), eq(OWNER), anyBoolean()))
                .thenThrow(new RuntimeException("存储不可用"));
        assertNull(parser.loadAttachmentText(attachDoc(".pdf", null)));
    }

    // ---- 节点 metadata ----

    @Test
    void buildNodeMetadata_attachMode_textIncluded() {
        stubFile("附件全文内容");
        String json = parser.buildNodeMetadata(attachDoc(".txt", null));

        assertTrue(json.contains("\"attachMode\":true"));
        assertTrue(json.contains("附件全文内容"));
        assertTrue(json.contains("/api/files/f1.txt"));
    }

    @Test
    void buildNodeMetadata_imageAttach_noAttachmentTextKey() {
        String json = parser.buildNodeMetadata(attachDoc(".png", null));

        assertTrue(json.contains("\"attachMode\":true"));
        assertTrue(json.contains("/api/files/f1.png"));
        assertFalse(json.contains("attachmentText"), "图片不预提取，metadata 不含 attachmentText");
        verify(fileStorageService, never()).load(any(), any(), anyBoolean());
    }

    @Test
    void buildNodeMetadata_nonAttachTextDoc_emptyJson() {
        KnowledgeDocument d = attachDoc(".txt", null);
        d.setDocType("TEXT");
        d.setParseOptions("{\"indexMode\":\"AUTO\"}");
        assertEquals("{}", parser.buildNodeMetadata(d));
    }
}
