package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeIndexJob;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.service.internal.IndexJobTxService;
import com.superprogrammer.knowledge.service.internal.L1Metadata;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import com.superprogrammer.knowledge.util.HashUtil;
import com.superprogrammer.knowledge.util.L1EmbedText;
import com.superprogrammer.llm.LlmGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 索引 job 消费者（v6 §6/§7.3.2，阶段2 第4项）。
 * 定时轮询认领 PENDING/RUNNING(过期) 的 UPSERT job → 提交到 knowledgeTaskExecutor 异步消费。
 *
 * LLM embed 阻塞且计费 → 并发受 executor 限制（core2/max4），轮询只负责认领+分发，不占 embed 时长。
 *
 * 处理流程（每 job）：
 *   1. 读 node，I2 re-check：null/deleted(@TableLogic 已滤)/status≠ACTIVE/content_hash≠job → voidJob（作废）
 *   2. 读 KB 取 embeddingModel（路由 provider + 写 embedding 行）
 *   3. LlmGateway.embed(L0 摘要, model)，维度校验 = 2048
 *   4. txService.completeUpsert：tx 内再校 node（I1）→ upsert 向量 → DONE → doc 可能 INDEXED
 *   异常 → txService.failJob：指数退避重试，超 max_attempt → DEAD
 *
 * 本类无 @Transactional：embed 必须在事务外；所有 DB 写经 IndexJobTxService 代理方法。
 */
@Slf4j
@Component
public class IndexJobWorker {

    private static final int BATCH = 8;
    private static final int MAX_ERROR_LEN = 1900;

    private final IndexJobTxService txService;
    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public IndexJobWorker(IndexJobTxService txService,
                          KnowledgeNodeMapper nodeMapper,
                          KnowledgeDocumentMapper documentMapper,
                          KnowledgeBaseService knowledgeBaseService,
                          LlmGateway llmGateway,
                          ObjectMapper objectMapper,
                          @Qualifier("knowledgeTaskExecutor") Executor executor) {
        this.txService = txService;
        this.nodeMapper = nodeMapper;
        this.documentMapper = documentMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${knowledge.index.poll-ms:5000}")
    public void poll() {
        try {
            List<KnowledgeIndexJob> jobs = txService.claimBatch(BATCH);
            if (jobs.isEmpty()) {
                return;
            }
            log.debug("认领索引 job {} 条", jobs.size());
            for (KnowledgeIndexJob job : jobs) {
                executor.execute(() -> process(job));
            }
        } catch (Exception e) {
            log.error("索引 job 轮询/认领失败: {}", e.getMessage(), e);
        }
    }

    private void process(KnowledgeIndexJob job) {
        try {
            // Phase3：doc 级 L1 向量 job（无 node）走独立分支，避免 nodeMapper.selectById(null)
            if ("UPSERT_L1".equals(job.getJobType())) {
                processUpsertL1(job);
                return;
            }
            // I2 re-check（embed 前）：node 存在/ACTIVE/hash 一致
            KnowledgeNode node = nodeMapper.selectById(job.getNodeId());
            if (node == null
                    || !"ACTIVE".equals(node.getStatus())
                    || !eq(node.getContentHash(), job.getContentHash())) {
                txService.voidJob(job.getId(), "节点已变更/失活/删除，job 作废（新版本 job 接管）");
                return;
            }

            KnowledgeBase kb = knowledgeBaseService.ensure(job.getKbId());
            String embeddingModel = kb.getEmbeddingModel();

            float[] vector = llmGateway.embed(node.getContent(), embeddingModel);
            if (vector.length != HalfVecUtil.DIM) {
                throw new RuntimeException("embedding 维度不匹配 expected=" + HalfVecUtil.DIM
                        + " actual=" + vector.length);
            }
            String halfvec = HalfVecUtil.toHalfVec(vector);

            txService.completeUpsert(job.getId(), node.getId(), node.getDocumentId(),
                    job.getKbId(), embeddingModel, halfvec, node.getContentHash());
            log.info("索引完成 nodeId={} kbId={} model={}", node.getId(), job.getKbId(), embeddingModel);
        } catch (Exception e) {
            log.error("索引 job 处理失败 jobId={}: {}", job.getId(), e.getMessage(), e);
            txService.failJob(job.getId(), truncate(e.getMessage(), MAX_ERROR_LEN));
        }
    }

    /**
     * UPSERT_L1（Phase3，doc 级 L1 向量）：读 doc.l1_metadata → 拼 L1 文本（summary+outline+rules）
     * → I2 hash 复校 → embed → completeUpsertL1（tx 内二次复校 + upsert L1 向量行）。
     * doc 删/l1 空/l1 变更/文本空 → voidJob（新版本 job 接管）。
     */
    private void processUpsertL1(KnowledgeIndexJob job) {
        try {
            KnowledgeDocument doc = documentMapper.selectById(job.getDocumentId());
            if (doc == null || doc.getL1Metadata() == null || doc.getL1Metadata().isBlank()) {
                txService.voidJob(job.getId(), "文档已删除或无 L1 元数据，L1 job 作废");
                return;
            }
            L1Metadata l1;
            try {
                l1 = objectMapper.readValue(doc.getL1Metadata(), L1Metadata.class);
            } catch (Exception e) {
                txService.voidJob(job.getId(), "L1 元数据解析失败，L1 job 作废: " + e.getMessage());
                return;
            }
            String text = L1EmbedText.build(l1);
            String l1Hash = HashUtil.sha256(text);
            if (!eq(l1Hash, job.getContentHash())) {
                txService.voidJob(job.getId(), "L1 元数据已变更，L1 job 作废（新版本 job 接管）");
                return;
            }
            if (text.isBlank()) {
                txService.voidJob(job.getId(), "L1 文本为空（summary/outline/rules 全空），L1 job 作废");
                return;
            }

            KnowledgeBase kb = knowledgeBaseService.ensure(job.getKbId());
            String embeddingModel = kb.getEmbeddingModel();

            float[] vector = llmGateway.embed(text, embeddingModel);
            if (vector.length != HalfVecUtil.DIM) {
                throw new RuntimeException("L1 embedding 维度不匹配 expected=" + HalfVecUtil.DIM
                        + " actual=" + vector.length);
            }
            String halfvec = HalfVecUtil.toHalfVec(vector);

            txService.completeUpsertL1(job.getId(), job.getDocumentId(), job.getKbId(),
                    embeddingModel, halfvec, l1Hash);
            log.info("L1 索引完成 docId={} kbId={} model={}", job.getDocumentId(), job.getKbId(), embeddingModel);
        } catch (Exception e) {
            log.error("索引 job 处理失败 jobId={}: {}", job.getId(), e.getMessage(), e);
            txService.failJob(job.getId(), truncate(e.getMessage(), MAX_ERROR_LEN));
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
