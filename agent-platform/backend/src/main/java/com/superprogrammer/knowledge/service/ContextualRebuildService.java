package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.dto.ContextualRebuildVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentVersionMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WP3 C4 存量可选重建编排（非事务：LLM 定位表生成秒级阻塞+计费，不占 DB 事务）。
 * 单库粒度；只处理 INDEXED 且非 ATTACHMENT 文档（附件描述召回豁免，规格 §6.3）；
 * 中断可续：文档所有 L2 已有定位语 → 跳过（LLM+job 都不重跑），doc 粒度幂等。
 *
 * 每文档：DB 节点构 ChunkBrief（path/标题/首行）→ 1 次 LLM 定位表（复用 L1 摘要，
 * 计费归户 docOwner）→ tx 段写回 contextual_text + 新 contextHash + REINDEX job
 * （pipeline=CTX_LLM_V1，worker 侧自动重嵌+OS 双写）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextualRebuildService {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final LlmContextualizer llmContextualizer;
    private final ContextualRebuildTxService txService;
    private final ObjectMapper objectMapper;

    /** 成本预估（确认框展示）：文档数/分块数/LLM 调用次数（=文档数）。 */
    public ContextualRebuildVO estimate(Long kbId) {
        List<KnowledgeDocument> docs = loadIndexedDocs(kbId);
        int skippedAttachment = countAttachment(docs);
        List<KnowledgeDocument> parseable = parseableDocs(docs);
        return ContextualRebuildVO.estimate(parseable.size(), countL2Chunks(parseable), skippedAttachment);
    }

    /** 应用 LLM 上下文增强（逐文档：已完成跳过→LLM→事务写回+入队）。 */
    public ContextualRebuildVO apply(Long kbId) {
        KnowledgeBase kb = knowledgeBaseService.ensure(kbId);
        String embeddingModel = kb.getEmbeddingModel();
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalStateException("知识库未配置可用 embedding 模型 kbId=" + kbId);
        }
        List<KnowledgeDocument> docs = loadIndexedDocs(kbId);
        int skippedAttachment = countAttachment(docs);
        int appliedDocs = 0;
        int skippedDone = 0;
        int enqueued = 0;
        for (KnowledgeDocument doc : parseableDocs(docs)) {
            List<KnowledgeNode> l2 = loadActiveL2(doc.getId());
            if (l2.isEmpty()) {
                continue;
            }
            if (l2.stream().allMatch(node -> hasText(node.getContextualText()))) {
                skippedDone++;   // 中断可续：整文档已完成，LLM 与 job 都不重跑
                continue;
            }
            Map<String, String> locators = llmContextualizer.generateLocators(
                    doc, l1SummaryOf(doc), briefsOf(l2), doc.getCreatedBy());
            enqueued += txService.applyContextualLocators(
                    doc, embeddingModel.trim(), parserVersionOf(doc), locators, l2);
            appliedDocs++;
        }
        log.info("存量上下文增强完成 kbId={} docs={} applied={} skippedDone={} skippedAttachment={} enqueued={}",
                kbId, docs.size(), appliedDocs, skippedDone, skippedAttachment, enqueued);
        return ContextualRebuildVO.applied(docs.size(), appliedDocs, skippedDone, skippedAttachment, enqueued);
    }

    /** 本库 INDEXED 文档（含 ATTACHMENT——供豁免计数；deleted 由 @TableLogic 滤）。 */
    private List<KnowledgeDocument> loadIndexedDocs(Long kbId) {        LambdaQueryWrapper<KnowledgeDocument> w = new LambdaQueryWrapper<>();
        w.eq(KnowledgeDocument::getKbId, kbId)
                .eq(KnowledgeDocument::getStatus, "INDEXED")
                .select(KnowledgeDocument::getId, KnowledgeDocument::getKbId,
                        KnowledgeDocument::getTitle, KnowledgeDocument::getDocType,
                        KnowledgeDocument::getCurrentVersionId, KnowledgeDocument::getL1Metadata,
                        KnowledgeDocument::getCreatedBy);
        return documentMapper.selectList(w);
    }

    /** 预估用分块总数（与 apply 同一 L2 口径：ACTIVE + C2/E3）。 */
    private int countL2Chunks(List<KnowledgeDocument> parseable) {
        int total = 0;
        for (KnowledgeDocument doc : parseable) {
            total += loadActiveL2(doc.getId()).size();
        }
        return total;
    }

    private static List<KnowledgeDocument> parseableDocs(List<KnowledgeDocument> docs) {
        return docs.stream().filter(doc -> !"ATTACHMENT".equals(doc.getDocType())).toList();
    }

    private static int countAttachment(List<KnowledgeDocument> docs) {
        return (int) docs.stream().filter(doc -> "ATTACHMENT".equals(doc.getDocType())).count();
    }

    /** 文档下 ACTIVE L2（C2/E3，兼容存量 granularity 缺省 C2）分块。 */
    private List<KnowledgeNode> loadActiveL2(Long docId) {
        LambdaQueryWrapper<KnowledgeNode> w = new LambdaQueryWrapper<>();
        w.eq(KnowledgeNode::getDocumentId, docId)
                .eq(KnowledgeNode::getLevel, "L2")
                .eq(KnowledgeNode::getStatus, "ACTIVE")
                .select(KnowledgeNode::getId, KnowledgeNode::getDocumentId, KnowledgeNode::getKbId,
                        KnowledgeNode::getParentId, KnowledgeNode::getLevel, KnowledgeNode::getNodeType,
                        KnowledgeNode::getTitle, KnowledgeNode::getPath, KnowledgeNode::getContent,
                        KnowledgeNode::getContentHash, KnowledgeNode::getContextHash,
                        KnowledgeNode::getContextualText, KnowledgeNode::getMetadata,
                        KnowledgeNode::getVersionId, KnowledgeNode::getStatus, KnowledgeNode::getTenantId);
        return nodeMapper.selectList(w).stream()
                .filter(node -> {
                    String granularity = granularityOf(node);
                    return "C2".equals(granularity) || "E3".equals(granularity);
                }).toList();
    }

    private String granularityOf(KnowledgeNode node) {
        String metadata = node.getMetadata();
        if (metadata == null || metadata.isBlank()) {
            return "C2";
        }
        try {
            String v = objectMapper.readTree(metadata).path("granularity").asText(null);
            return v == null || v.isBlank() ? "C2" : v;
        } catch (Exception e) {
            return "C2";
        }
    }

    /** DB 节点 → LLM chunk 清单（title=L2 节点标题=原 section 标题；首行截 ~200 与 writer 口径近似）。 */
    private List<LlmContextualizer.ChunkBrief> briefsOf(List<KnowledgeNode> l2) {
        List<LlmContextualizer.ChunkBrief> briefs = new ArrayList<>(l2.size());
        for (KnowledgeNode node : l2) {
            briefs.add(new LlmContextualizer.ChunkBrief(node.getPath(),
                    node.getTitle() == null ? "" : node.getTitle(), firstLine(node.getContent())));
        }
        return briefs;
    }

    private static String firstLine(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String line = content.trim();
        int nl = line.indexOf('\n');
        return nl > 0 ? line.substring(0, Math.min(nl, 200)) : line.substring(0, Math.min(line.length(), 200));
    }

    /** L1 摘要（l1_metadata JSON summary 字段；缺失/坏 JSON → null=LLM 纯靠 chunk 清单）。 */
    private String l1SummaryOf(KnowledgeDocument doc) {
        String l1 = doc.getL1Metadata();
        if (l1 == null || l1.isBlank()) {
            return null;
        }
        try {
            String summary = objectMapper.readTree(l1).path("summary").asText(null);
            return hasText(summary) ? summary.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前版本 parser 版本（版本行缺失 → null，job 指纹仍稳定）。 */
    private String parserVersionOf(KnowledgeDocument doc) {
        if (doc.getCurrentVersionId() == null) {
            return null;
        }
        com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion version =
                versionMapper.selectById(doc.getCurrentVersionId());
        return version == null ? null : version.getParserVersion();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
