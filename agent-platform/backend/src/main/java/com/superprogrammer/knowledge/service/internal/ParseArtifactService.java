package com.superprogrammer.knowledge.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentVersionMapper;
import com.superprogrammer.knowledge.util.HashUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/** 将结构化解析大对象写文件存储，并在不可变版本行保存引用和完整性 Hash。 */
@Service
@RequiredArgsConstructor
public class ParseArtifactService {

    public static final String STORAGE_SOURCE = "KB_PARSE_ARTIFACT";

    private final FileStorageService fileStorageService;
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    public void persistIfVersioned(KnowledgeDocument document, ExtractedDocument extracted) {
        if (document.getCurrentVersionId() != null) {
            persist(document, extracted);
        }
    }

    public void persist(KnowledgeDocument document, ExtractedDocument extracted) {
        if (document.getCurrentVersionId() == null) {
            throw new IllegalStateException("current document version is required for parse artifact");
        }
        try {
            byte[] json = objectMapper.writeValueAsBytes(extracted);
            String artifactHash = HashUtil.sha256(json);
            String fileId = fileStorageService.storeStream(
                    new ByteArrayInputStream(json),
                    "document-" + document.getId() + "-parse.json",
                    "application/json", (long) json.length,
                    document.getCreatedBy(), STORAGE_SOURCE);
            String artifactRef = "/api/files/" + fileId;
            extracted.setArtifactRef(artifactRef);
            int updated = versionMapper.updateParseArtifact(document.getCurrentVersionId(),
                    extracted.getParserVersion(), artifactRef, artifactHash);
            if (updated != 1) {
                throw new IllegalStateException("current document version not found");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to persist parse artifact", e);
        }
    }
}
