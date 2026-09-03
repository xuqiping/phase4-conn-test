package com.superprogrammer.knowledge.service;

import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeIndexJob;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.mapper.KnowledgeIndexJobMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * WP3 C4 存量重建的事务段：定位表写回 L2 节点 + 全指纹 REINDEX job 入队，单文档一事务。
 * 独立 bean：ContextualRebuildService（非事务，LLM 调用秒级阻塞须在事务外）跨 bean 调用
 * 本类的 @Transactional 方法，经 Spring 代理生效（同类自调绕代理的坑，见 KnowledgeNodeWriter 说明）。
 *
 * 幂等（中断可续）：job idempotency_key 含新 contextHash + pipeline CTX_LLM_V1，
 * ON CONFLICT DO NOTHING——中断重跑时已 DONE 的 job 不重复入队；
 * 节点 contextual_text 重写为同值 → hash 不变 → key 不变 → 跳过。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextualRebuildTxService {

    /** 与 KnowledgeNodeWriter 的 LLM 定位表管线同标识：新旧管线 job 幂等键天然隔离。 */
    static final String PIPELINE_CTX_LLM_V1 = "CTX_LLM_V1";

    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeIndexJobMapper indexJobMapper;
    private final Contextualizer contextualizer;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * 单文档事务：逐 L2 节点写 contextual_text（命中定位表）+ 按新公式重算 context_hash
     * → 入队 REINDEX job（全指纹：内容/上下文/版本/模型/管线，任一变化即新 job 接管）。
     *
     * @param l2Nodes 调用方已加载的本批 L2 节点（事务内直接用内存值更新；节点在 LLM 调用期间
     *                若被并发改写，job 的 hash 指纹与 worker 侧 I2 复校会兜底作废）
     * @return 新入队 job 数（ON CONFLICT 跳过不计）
     */
    @Transactional(rollbackFor = Exception.class)
    public int applyContextualLocators(KnowledgeDocument doc, String embeddingModel,
                                       String parserVersion, Map<String, String> locators,
                                       List<KnowledgeNode> l2Nodes) {
        int enqueued = 0;
        for (KnowledgeNode node : l2Nodes) {
            String locator = locators.get(node.getPath());
            if (locator != null && !locator.isBlank()) {
                node.setContextualText(locator);
            }
            com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion version =
                    new com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion();
            version.setId(node.getVersionId());
            Contextualizer.ContextualContent contextual = contextualizer.contextualize(doc, version, node);
            node.setContextHash(contextual.contextHash());
            nodeMapper.updateById(node);

            KnowledgeIndexJob job = new KnowledgeIndexJob();
            job.setNodeId(node.getId());
            job.setKbId(doc.getKbId());
            job.setJobType("REINDEX");
            job.setContentHash(node.getContentHash());
            job.setContextHash(node.getContextHash());
            job.setVersionId(node.getVersionId());
            job.setParserVersion(parserVersion);
            job.setChunkerVersion(chunkerVersionOf(node));
            job.setEmbeddingModel(embeddingModel);
            job.setPipelineVersion(PIPELINE_CTX_LLM_V1);
            job.setIdempotencyKey(HashUtil.sha256(node.getId() + ":" + node.getContentHash() + ":"
                    + node.getContextHash() + ":" + node.getVersionId() + ":" + parserVersion + ":"
                    + job.getChunkerVersion() + ":" + embeddingModel + ":"
                    + PIPELINE_CTX_LLM_V1 + ":REINDEX"));
            enqueued += indexJobMapper.insertNodeJobIgnoreConflict(job);
        }
        log.info("存量上下文增强写回 docId={} nodes={} enqueued={}", doc.getId(), l2Nodes.size(), enqueued);
        return enqueued;
    }

    /** chunker 版本取节点 metadata（与 enqueueSnapshotJobs SQL 的 COALESCE 口径一致），缺省 legacy。 */
    private String chunkerVersionOf(KnowledgeNode node) {
        String metadata = node.getMetadata();
        if (metadata == null || metadata.isBlank()) {
            return "legacy";
        }
        try {
            String v = objectMapper.readTree(metadata).path("chunkerVersion").asText(null);
            return v == null || v.isBlank() ? "legacy" : v;
        } catch (Exception e) {
            return "legacy";
        }
    }
}
