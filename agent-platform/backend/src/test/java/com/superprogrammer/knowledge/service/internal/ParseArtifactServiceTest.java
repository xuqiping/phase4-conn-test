package com.superprogrammer.knowledge.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentVersionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParseArtifactServiceTest {

    @Test
    void storesJsonOutsideDatabaseAndBindsHashesToCurrentVersion() throws Exception {
        FileStorageService storage = mock(FileStorageService.class);
        KnowledgeDocumentVersionMapper versionMapper = mock(KnowledgeDocumentVersionMapper.class);
        ParseArtifactService service = new ParseArtifactService(storage, versionMapper, new ObjectMapper());
        when(storage.storeStream(any(InputStream.class), eq("document-7-parse.json"),
                eq("application/json"), any(Long.class), eq(3L), eq("KB_PARSE_ARTIFACT")))
                .thenReturn("artifact-1.json");
        when(versionMapper.updateParseArtifact(eq(9L), eq("2"),
                eq("/api/files/artifact-1.json"), any(String.class))).thenReturn(1);
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(7L);
        doc.setCurrentVersionId(9L);
        doc.setCreatedBy(3L);
        ExtractedDocument extracted = ExtractedDocument.builder()
                .schemaVersion("1.0").parserName("pdfbox").parserVersion("2")
                .sourceHash("source-sha").documentType("PDF")
                .sections(List.of()).build();

        service.persist(doc, extracted);

        ArgumentCaptor<InputStream> stream = ArgumentCaptor.forClass(InputStream.class);
        verify(storage).storeStream(stream.capture(), eq("document-7-parse.json"),
                eq("application/json"), any(Long.class), eq(3L), eq("KB_PARSE_ARTIFACT"));
        String json = new String(stream.getValue().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(json).contains("pdfbox", "source-sha");
        verify(versionMapper).updateParseArtifact(eq(9L), eq("2"), eq("/api/files/artifact-1.json"),
                org.mockito.ArgumentMatchers.matches("[0-9a-f]{64}"));
        assertThat(extracted.getArtifactRef()).isEqualTo("/api/files/artifact-1.json");
    }
}
