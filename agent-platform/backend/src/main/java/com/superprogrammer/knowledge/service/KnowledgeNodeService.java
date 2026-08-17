package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.KnowledgeNodeVO;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeIndexJob;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeIndexJobMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.util.HashUtil;
import com.superprogrammer.knowledge.util.JiebaTokenizer;
import com.superprogrammer.knowledge.util.L1EmbedText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识节点查询（目录树，必做收口 #4）。
 * 当前仅读：文档下节点 flat 列表（L0 摘要 + L2 原文子节点，前端按 parentId 建树）。
 * 写入（解析落库）由 {@link com.superprogrammer.knowledge.service.KnowledgeNodeWriter} 负责。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeNodeService {

    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeIndexJobMapper indexJobMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    /**
     * 文档下节点列表（目录树）。canRead 门（doc 所属 KB）。
     * 排序：id 升序（parser 按 section 顺序写，L0 先于其 L2 子节点）→ 前端按 parentId 重建层级。
     * deleted 已由 @TableLogic 自动滤。
     */
    public List<KnowledgeNodeVO> listByDocument(Long docId, Long operatorId, boolean admin) {
        KnowledgeDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        com.superprogrammer.knowledge.entity.KnowledgeBase kb = knowledgeBaseService.ensure(doc.getKbId());
        if (!knowledgeBaseService.canRead(kb, operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该文档");
        }
        // 14x#3：保密库成员禁看切片原文（403，防列表+切片拼全文旁路；owner/admin 直通）
        KnowledgeConfidentialGuard.assertCanViewContent(kb, operatorId, admin);
        LambdaQueryWrapper<KnowledgeNode> w = new LambdaQueryWrapper<>();
        w.eq(KnowledgeNode::getDocumentId, docId)
                .orderByAsc(KnowledgeNode::getId);
        return nodeMapper.selectList(w).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 回填 content_tokens（Phase2 V35 迁移后存量节点 content_tokens IS NULL）。
     * 对 ACTIVE 节点逐条 jieba 分词 UPDATE。bm25HitsJieba 在未回填时空命中（优雅降级），
     * smoke-kb 必须回填才能验词法兜底。返回更新条数。
     */
    public int backfillContentTokens() {
        LambdaQueryWrapper<KnowledgeNode> w = new LambdaQueryWrapper<>();
        w.isNull(KnowledgeNode::getContentTokens)
                .eq(KnowledgeNode::getStatus, "ACTIVE")
                .select(KnowledgeNode::getId, KnowledgeNode::getContent);
        List<KnowledgeNode> nodes = nodeMapper.selectList(w);
        int updated = 0;
        for (KnowledgeNode n : nodes) {
            KnowledgeNode up = new KnowledgeNode();
            up.setId(n.getId());
            up.setContentTokens(JiebaTokenizer.tokenize(n.getContent()));
            nodeMapper.updateById(up);
            updated++;
        }
        return updated;
    }

    /**
     * 回填 L1 文档向量（Phase3 V36）：对存量 INDEXED 且有 l1_metadata 的文档入队 UPSERT_L1 job。
     * IndexJobWorker 异步 embed（L1 文本=summary+outline+importantRules）写入 knowledge_doc_embeddings_doubao。
     * 幂等：同 doc+同 l1 hash 的 job ON CONFLICT DO NOTHING（l1 变→新 hash 新 job 重嵌）。
     * smoke-kb 等 Phase3 前已解析的文档必须回填才能验 L1 通道。返回新入队条数。
     */
    public int backfillL1Embeddings() {
        LambdaQueryWrapper<KnowledgeDocument> w = new LambdaQueryWrapper<>();
        w.isNotNull(KnowledgeDocument::getL1Metadata)
                .eq(KnowledgeDocument::getStatus, "INDEXED")
                .select(KnowledgeDocument::getId, KnowledgeDocument::getKbId, KnowledgeDocument::getL1Metadata);
        List<KnowledgeDocument> docs = documentMapper.selectList(w);
        int enqueued = 0;
        for (KnowledgeDocument doc : docs) {
            String l1Hash = L1EmbedText.hashOfJson(doc.getL1Metadata(), objectMapper);
            KnowledgeIndexJob job = new KnowledgeIndexJob();
            job.setDocumentId(doc.getId());
            job.setKbId(doc.getKbId());
            job.setJobType("UPSERT_L1");
            job.setContentHash(l1Hash);
            job.setIdempotencyKey(HashUtil.sha256(doc.getId() + ":" + l1Hash + ":UPSERT_L1"));
            enqueued += indexJobMapper.insertL1JobIgnoreConflict(job);
        }
        return enqueued;
    }

    private KnowledgeNodeVO toVO(KnowledgeNode n) {
        return KnowledgeNodeVO.builder()
                .id(n.getId())
                .parentId(n.getParentId())
                .documentId(n.getDocumentId())
                .level(n.getLevel())
                .nodeType(n.getNodeType())
                .title(n.getTitle())
                .content(n.getContent())
                .tokenCount(n.getTokenCount())
                .status(n.getStatus())
                .build();
    }
}
