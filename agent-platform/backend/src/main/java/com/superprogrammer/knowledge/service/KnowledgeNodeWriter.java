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
import com.superprogrammer.knowledge.util.HashUtil;
import com.superprogrammer.knowledge.util.TokenEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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
    private static final int L2_MAX_TOKENS = 1024;

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeIndexJobMapper indexJobMapper;

    /**
     * @param doc        待解析文档（读 kbId/title/id）
     * @param operatorId 写入审计字段
     * @param extracted  Tika 抽取 + 切分
     * @param l1Json     L1 元数据 JSON（写 knowledge_documents.l1_metadata）
     * @param abstracts  与 extracted.sections 对齐的 L0 摘要列表；空/blank 走兜底
     */
    @Transactional(rollbackFor = Exception.class)
    public void writeNodes(KnowledgeDocument doc, Long operatorId,
                           ExtractedDocument extracted, String l1Json, List<String> abstracts) {
        // 1. doc → EMBEDDING + l1_metadata，清 parse_error
        LambdaUpdateWrapper<KnowledgeDocument> docUpdate = new LambdaUpdateWrapper<>();
        docUpdate.eq(KnowledgeDocument::getId, doc.getId())
                .set(KnowledgeDocument::getStatus, "EMBEDDING")
                .set(KnowledgeDocument::getL1Metadata, l1Json)
                .set(KnowledgeDocument::getParseError, null)
                .set(KnowledgeDocument::getUpdatedBy, operatorId);
        documentMapper.update(null, docUpdate);

        List<Section> sections = extracted.getSections();
        if (sections == null || sections.isEmpty()) {
            // 退化：0 section，不产节点（doc 已 EMBEDDING，worker 无 job 可消费）
            log.warn("文档无 section，仅置 EMBEDDING docId={}", doc.getId());
            return;
        }

        // 2. 每 section：1 个 L0（摘要=内容）+ 其 L2 子节点（原文切片）
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            String abstract0 = pickAbstract(section, abstracts, i);

            KnowledgeNode l0 = buildNode(doc, null, "L0", section.getTitle(), abstract0,
                    "/L0-" + i);
            l0.setContentHash(HashUtil.sha256(abstract0));
            l0.setTokenCount(TokenEstimator.estimate(abstract0));
            nodeMapper.insert(l0);                  // id 回填

            indexJobMapper.insert(buildUpsertJob(l0, doc.getKbId()));  // 仅 L0 建 job（I4）

            int j = 0;
            for (String chunk : splitL2(section.getContent())) {
                KnowledgeNode l2 = buildNode(doc, l0.getId(), "L2", section.getTitle(), chunk,
                        "/L0-" + i + "/L2-" + j);
                l2.setContentHash(HashUtil.sha256(chunk));
                l2.setTokenCount(TokenEstimator.estimate(chunk));
                nodeMapper.insert(l2);              // L2 不向量化、无 job
                j++;
            }
        }
        log.info("文档落库完成 docId={} sections={} ", doc.getId(), sections.size());
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
                                    String title, String content, String path) {
        KnowledgeNode node = new KnowledgeNode();
        node.setTenantId(TENANT_ID);
        node.setKbId(doc.getKbId());
        node.setDocumentId(doc.getId());
        node.setParentId(parentId);
        node.setPath(path);
        node.setNodeType("SECTION");
        node.setLevel(level);
        node.setTitle(title);
        node.setContent(content);
        node.setContentTokens(com.superprogrammer.knowledge.util.JiebaTokenizer.tokenize(content));
        node.setMetadata("{}");
        node.setStatus("ACTIVE");
        node.setCreatedBy(nullSafe(doc.getCreatedBy()));
        node.setUpdatedBy(nullSafe(doc.getCreatedBy()));
        return node;
    }

    /** UPSERT job：仅 L0。idempotency_key=sha256(nodeId:contentHash:UPSERT)（I4）。 */
    private KnowledgeIndexJob buildUpsertJob(KnowledgeNode l0, Long kbId) {
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setNodeId(l0.getId());
        job.setKbId(kbId);
        job.setJobType("UPSERT");
        job.setContentHash(l0.getContentHash());
        job.setIdempotencyKey(HashUtil.sha256(l0.getId() + ":" + l0.getContentHash() + ":UPSERT"));
        return job;
    }

    /** L2 原文按 ≤1024 tok（≈4096 字符）切片，按段落边界累积，超长单段硬切。 */
    private List<String> splitL2(String content) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }
        int maxChars = L2_MAX_TOKENS * 4;
        String[] paras = content.split("\n\n+");
        StringBuilder buf = new StringBuilder();
        for (String para : paras) {
            String p = para.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (buf.length() + p.length() + 2 > maxChars && buf.length() > 0) {
                chunks.add(buf.toString());
                buf.setLength(0);
            }
            if (p.length() > maxChars) {
                if (buf.length() > 0) {
                    chunks.add(buf.toString());
                    buf.setLength(0);
                }
                for (int s = 0; s < p.length(); s += maxChars) {
                    chunks.add(p.substring(s, Math.min(p.length(), s + maxChars)));
                }
            } else {
                if (buf.length() > 0) {
                    buf.append("\n\n");
                }
                buf.append(p);
            }
        }
        if (buf.length() > 0) {
            chunks.add(buf.toString());
        }
        return chunks;
    }

    private Long nullSafe(Long v) {
        return v == null ? 0L : v;
    }
}
