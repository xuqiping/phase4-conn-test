package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeIndexJob;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeIndexJobMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.service.internal.ExtractedDocument;
import com.superprogrammer.knowledge.service.internal.Section;
import com.superprogrammer.knowledge.chunk.ChunkDraft;
import com.superprogrammer.knowledge.chunk.ChunkFactory;
import com.superprogrammer.common.metrics.BizMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.util.HashUtil;
import com.superprogrammer.knowledge.util.L1EmbedText;
import com.superprogrammer.knowledge.util.TokenEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;

/**
 * 解析产物落库（v6 §6 单事务）。
 * 独立 bean：DocumentParserService.parse()（非事务）跨 bean 调用本类的 @Transactional writeNodes，
 * 经 Spring 代理，事务生效（同类自调会绕过代理）。
 *
 * doc 状态更新用 UpdateWrapper（绕过 @Version，避免单次解析内多次更新同一内存实体导致版本失配）。
 * 节点/job 用 mapper.insert 顺序写：L0 先插→读回填 id→设 L2.parentId→插 L2，保证 parent_id 完整。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeNodeWriter {

    private static final Long TENANT_ID = 1L;
    private static final String CHUNKER_VERSION = "1";

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeIndexJobMapper indexJobMapper;
    private final ObjectMapper objectMapper;
    private final ChunkFactory chunkFactory;
    private final BizMetrics bizMetrics;
    private final KnowledgeBaseService knowledgeBaseService;
    private final Contextualizer contextualizer;
    @org.springframework.beans.factory.annotation.Value("${rag.index.pipeline-version:rag-index-v1}")
    private String pipelineVersion;

    /**
     * @param doc          待解析文档（读 kbId/title/id）
     * @param operatorId   写入审计字段
     * @param extracted    Tika 抽取 + 切分
     * @param l1Json       L1 元数据 JSON（写 knowledge_documents.l1_metadata）
     * @param abstracts    与 extracted.sections 对齐的 L0 摘要列表；空/blank 走兜底
     * @param metadataJson 节点 metadata JSON（IMAGE/FILE 注入 fileRef/mime/originalName；普通文档传 "{}"）
     */
    @Transactional(rollbackFor = Exception.class)
    public void writeNodes(KnowledgeDocument doc, Long operatorId,
                           ExtractedDocument extracted, String l1Json, List<String> abstracts,
                           String metadataJson) {
        long startedAt = System.nanoTime();
        requireOwnership(doc);
        // 1. doc → EMBEDDING + l1_metadata，清 parse_error
        LambdaUpdateWrapper<KnowledgeDocument> docUpdate = new LambdaUpdateWrapper<>();
        docUpdate.eq(KnowledgeDocument::getId, doc.getId())
                .set(KnowledgeDocument::getStatus, "EMBEDDING")
                .set(KnowledgeDocument::getL1Metadata, l1Json)
                .set(KnowledgeDocument::getParseError, null)
                .set(KnowledgeDocument::getUpdatedBy, operatorId);
        documentMapper.update(null, docUpdate);

        // Phase3：doc 级 L1 向量 job（L1 文本 embed），l1_metadata 非空即建（0 section 也建，doc 仍可经 L1 召回）
        if (l1Json != null && !l1Json.isBlank()) {
            indexJobMapper.insertL1JobIgnoreConflict(buildL1UpsertJob(doc, doc.getKbId(), l1Json));
        }

        List<Section> sections = extracted.getSections();
        if (sections == null || sections.isEmpty()) {
            // 退化：0 section，不产节点（doc 已 EMBEDDING，worker 无 job 可消费）
            log.warn("文档无 section，仅置 EMBEDDING docId={}", doc.getId());
            return;
        }

        int c2Count = 0;
        int e3Count = 0;
        // 2. 每 section：1 个兼容 L0/S1（摘要）+ 其兼容 L2/C2|E3 子节点
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            String abstract0 = pickAbstract(section, abstracts, i);

            String s1Path = "/L0-" + i;
            KnowledgeNode l0 = buildNode(doc, null, "L0", section.getNodeType(), section,
                    abstract0, s1Path, mergeS1Metadata(metadataJson, doc, section, i, sections.size()));
            l0.setContentHash(HashUtil.sha256(abstract0));
            l0.setTokenCount(TokenEstimator.estimate(abstract0));
            nodeMapper.insert(l0);                  // id 回填

            indexJobMapper.insertNodeJobIgnoreConflict(
                    buildUpsertJob(l0, doc.getKbId(), extracted.getParserVersion()));

            List<ChunkDraft> chunks = chunkFactory.chunk(section);
            for (ChunkDraft chunk : chunks) {
                if ("E3".equals(chunk.granularity())) e3Count++; else c2Count++;
                String path = s1Path + "/L2-" + chunk.ordinal();
                KnowledgeNode l2 = buildNode(doc, l0.getId(), "L2", chunk.chunkType(), section,
                        chunk.content(), path, mergeChunkMetadata(metadataJson, doc, section, chunk, s1Path));
                l2.setContentHash(HashUtil.sha256(chunk.content()));
                l2.setTokenCount(chunk.tokenCount());
                com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion version =
                        new com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion();
                version.setId(doc.getCurrentVersionId());
                Contextualizer.ContextualContent contextual = contextualizer.contextualize(doc, version, l2);
                l2.setContextHash(contextual.contextHash());
                nodeMapper.insert(l2);
                indexJobMapper.insertNodeJobIgnoreConflict(
                        buildContextualUpsertJob(l2, doc.getKbId(), extracted.getParserVersion()));
            }
        }
        bizMetrics.knowledgeChunked("S1", sections.size());
        bizMetrics.knowledgeChunked("C2", c2Count);
        bizMetrics.knowledgeChunked("E3", e3Count);
        Duration duration = Duration.ofNanos(System.nanoTime() - startedAt);
        bizMetrics.knowledgeChunkDuration(duration);
        log.info("知识分块落库完成 docId={} versionId={} chunkerVersion={} s1={} c2={} e3={} elapsedMs={}",
                doc.getId(), doc.getCurrentVersionId(), CHUNKER_VERSION,
                sections.size(), c2Count, e3Count, duration.toMillis());
    }

    /** 摘要为空（BATCH 未匹配 / HYBRID 未覆盖 / LLM 失败）→ 兜底 section 原文前 ~400 字。禁止空 content L0。 */
    private String pickAbstract(Section section, List<String> abstracts, int i) {
        if (abstracts != null && i < abstracts.size()) {
            String a = abstracts.get(i);
            if (a != null && !a.isBlank()) {
                return a.trim();
            }
        }
        String content = section.getContent() == null ? "" : section.getContent();
        String fallback = content.length() > 400 ? content.substring(0, 400) : content;
        return fallback.isBlank() ? section.getTitle() : fallback;
    }

    private KnowledgeNode buildNode(KnowledgeDocument doc, Long parentId, String level,
                                    String nodeType, Section section, String content,
                                    String path, String metadataJson) {
        KnowledgeNode node = new KnowledgeNode();
        node.setTenantId(TENANT_ID);
        node.setKbId(doc.getKbId());
        node.setDocumentId(doc.getId());
        node.setParentId(parentId);
        node.setPath(path);
        node.setNodeType(nodeType == null || nodeType.isBlank() ? "SECTION" : nodeType);
        node.setLevel(level);
        node.setTitle(section.getTitle());
        node.setContent(content);
        node.setContentTokens(com.superprogrammer.knowledge.util.JiebaTokenizer.tokenize(content));
        node.setMetadata(metadataJson);
        node.setStatus("ACTIVE");
        node.setVersionId(doc.getCurrentVersionId());
        node.setCreatedBy(nullSafe(doc.getCreatedBy()));
        node.setUpdatedBy(nullSafe(doc.getCreatedBy()));
        return node;
    }

    private String mergeS1Metadata(String metadataJson, KnowledgeDocument doc, Section section,
                                   int sectionIndex, int sectionCount) {
        Map<String, Object> additions = new LinkedHashMap<>();
        additions.put("granularity", "S1");
        additions.put("chunkType", section.getNodeType());
        additions.put("chunkerVersion", CHUNKER_VERSION);
        additions.put("previousPath", sectionIndex == 0 ? null : "/L0-" + (sectionIndex - 1));
        additions.put("nextPath", sectionIndex + 1 < sectionCount ? "/L0-" + (sectionIndex + 1) : null);
        return mergeMetadata(metadataJson, doc, section, additions);
    }

    private String mergeChunkMetadata(String metadataJson, KnowledgeDocument doc,
                                      Section section, ChunkDraft chunk,
                                      String parentPath) {
        Map<String, Object> additions = new LinkedHashMap<>();
        additions.put("granularity", chunk.granularity());
        additions.put("chunkType", chunk.chunkType());
        additions.put("chunkerVersion", CHUNKER_VERSION);
        additions.put("chunkOrdinal", chunk.ordinal());
        additions.put("parentPath", parentPath);
        additions.put("previousPath", chunk.previousOrdinal() == null
                ? null : parentPath + "/L2-" + chunk.previousOrdinal());
        additions.put("nextPath", chunk.nextOrdinal() == null
                ? null : parentPath + "/L2-" + chunk.nextOrdinal());
        additions.put("locator", chunk.locator());
        return mergeMetadata(metadataJson, doc, section, additions);
    }

    private String mergeMetadata(String metadataJson, KnowledgeDocument doc,
                                 Section section, Map<String, Object> additions) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (metadataJson != null && !metadataJson.isBlank()) {
                metadata.putAll(objectMapper.readValue(metadataJson, Map.class));
            }
            metadata.put("sectionId", section.getSectionId());
            metadata.put("parentSectionId", section.getParentSectionId());
            metadata.put("titlePath", section.getTitlePath());
            metadata.put("ordinal", section.getOrdinal());
            metadata.put("locator", section.getLocator());
            metadata.put("tenantId", TENANT_ID);
            metadata.put("kbId", doc.getKbId());
            metadata.put("documentId", doc.getId());
            metadata.put("versionId", doc.getCurrentVersionId());
            metadata.put("ownerId", doc.getOwnerId() == null ? doc.getCreatedBy() : doc.getOwnerId());
            metadata.put("authorityLevel", doc.getAuthorityLevel());
            metadata.put("confidentialityLevel", doc.getConfidentialityLevel());
            metadata.putAll(additions);
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid node metadata JSON", e);
        }
    }

    private void requireOwnership(KnowledgeDocument doc) {
        if (doc == null || doc.getId() == null || doc.getKbId() == null) {
            throw new IllegalArgumentException("knowledge chunk requires documentId and kbId");
        }
    }

    /** UPSERT job：仅 L0。idempotency_key=sha256(nodeId:contentHash:UPSERT)（I4）。 */
    private KnowledgeIndexJob buildUpsertJob(KnowledgeNode l0, Long kbId, String parserVersion) {
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setNodeId(l0.getId());
        job.setKbId(kbId);
        job.setJobType("UPSERT");
        job.setContentHash(l0.getContentHash());
        fillVersionFingerprint(job, l0.getVersionId(), parserVersion, kbId);
        job.setIdempotencyKey(HashUtil.sha256(l0.getId() + ":" + l0.getContentHash() + ":"
                + l0.getVersionId() + ":" + parserVersion + ":" + CHUNKER_VERSION + ":"
                + job.getEmbeddingModel() + ":" + job.getPipelineVersion() + ":UPSERT"));
        return job;
    }

    /** C2/E3 上下文化索引任务；正文或上下文任一变化都会生成新的幂等键。 */
    private KnowledgeIndexJob buildContextualUpsertJob(KnowledgeNode node, Long kbId, String parserVersion) {
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setNodeId(node.getId());
        job.setKbId(kbId);
        job.setJobType("UPSERT");
        job.setContentHash(node.getContentHash());
        job.setContextHash(node.getContextHash());
        fillVersionFingerprint(job, node.getVersionId(), parserVersion, kbId);
        job.setIdempotencyKey(HashUtil.sha256(node.getId() + ":" + node.getContentHash()
                + ":" + node.getContextHash() + ":" + node.getVersionId() + ":" + parserVersion + ":"
                + CHUNKER_VERSION + ":" + job.getEmbeddingModel() + ":" + job.getPipelineVersion() + ":UPSERT"));
        return job;
    }

    private void fillVersionFingerprint(KnowledgeIndexJob job, Long versionId,
                                        String parserVersion, Long kbId) {
        String embeddingModel = knowledgeBaseService.ensure(kbId).getEmbeddingModel();
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalStateException("知识库未配置可用 embedding 模型 kbId=" + kbId);
        }
        job.setVersionId(versionId);
        job.setParserVersion(parserVersion);
        job.setChunkerVersion(CHUNKER_VERSION);
        job.setEmbeddingModel(embeddingModel.trim());
        job.setPipelineVersion(pipelineVersion == null || pipelineVersion.isBlank()
                ? "rag-index-v1" : pipelineVersion.trim());
    }

    /**
     * UPSERT_L1 job：doc 级 L1 向量（Phase3）。
     * idempotency_key=sha256(docId:l1hash:UPSERT_L1)（I4），l1hash=L1 文本（summary+outline+rules）sha256。
     * content_hash=l1hash（worker embed 时算同款文本 hash，tx 内复校防中途变更）。
     */
    private KnowledgeIndexJob buildL1UpsertJob(KnowledgeDocument doc, Long kbId, String l1Json) {
        String l1Hash = L1EmbedText.hashOfJson(l1Json, objectMapper);
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setDocumentId(doc.getId());
        job.setKbId(kbId);
        job.setJobType("UPSERT_L1");
        job.setContentHash(l1Hash);
        fillVersionFingerprint(job, doc.getCurrentVersionId(), null, kbId);
        job.setIdempotencyKey(HashUtil.sha256(doc.getId() + ":" + l1Hash + ":"
                + doc.getCurrentVersionId() + ":" + job.getEmbeddingModel() + ":"
                + job.getPipelineVersion() + ":UPSERT_L1"));
        return job;
    }

    private Long nullSafe(Long v) {
        return v == null ? 0L : v;
    }
}
