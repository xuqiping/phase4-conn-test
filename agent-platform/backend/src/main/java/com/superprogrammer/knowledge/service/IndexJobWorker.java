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
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.llm.LlmGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
    private final FileStorageService fileStorageService;
    private final boolean retainAfterIndex;
    /** 运维系统 OPS-FR-05：queue.depth Gauge + indexed.total 指标。 */
    private final com.superprogrammer.common.metrics.BizMetrics bizMetrics;

    public IndexJobWorker(IndexJobTxService txService,
                          KnowledgeNodeMapper nodeMapper,
                          KnowledgeDocumentMapper documentMapper,
                          KnowledgeBaseService knowledgeBaseService,
                          LlmGateway llmGateway,
                          ObjectMapper objectMapper,
                          @Qualifier("knowledgeTaskExecutor") Executor executor,
                          FileStorageService fileStorageService,
                          @Value("${app.files.retain-after-index:false}") boolean retainAfterIndex,
                          com.superprogrammer.common.metrics.BizMetrics bizMetrics) {
        this.txService = txService;
        this.nodeMapper = nodeMapper;
        this.documentMapper = documentMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.fileStorageService = fileStorageService;
        this.retainAfterIndex = retainAfterIndex;
        this.bizMetrics = bizMetrics;
    }

    /** OPS-FR-05：启动即注册 queue.depth Gauge（否则首次 scrape 前无值/NaN）。回调为轻 count 查询。 */
    @jakarta.annotation.PostConstruct
    void registerQueueDepthGauge() {
        bizMetrics.registerIndexQueueDepth(txService::countClaimable);
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
                bizMetrics.indexed(com.superprogrammer.common.metrics.BizMetrics.INDEX_VOID);
                return;
            }

            KnowledgeBase kb = knowledgeBaseService.ensure(job.getKbId());
            String embeddingModel = kb.getEmbeddingModel();

            // 计费归户：@Scheduled 轮询线程无请求上下文，按文档上传者（doc.createdBy）归户 embed 计费
            KnowledgeDocument doc = documentMapper.selectById(node.getDocumentId());
            Long docOwner = doc != null ? doc.getCreatedBy() : null;
            float[] vector = llmGateway.embed(node.getContent(), embeddingModel, docOwner);
            if (vector.length != HalfVecUtil.DIM) {
                throw new RuntimeException("embedding 维度不匹配 expected=" + HalfVecUtil.DIM
                        + " actual=" + vector.length);
            }
            String halfvec = HalfVecUtil.toHalfVec(vector);

            IndexJobTxService.IndexedDoc indexed = txService.completeUpsert(job.getId(), node.getId(), node.getDocumentId(),
                    job.getKbId(), embeddingModel, halfvec, node.getContentHash());
            cleanOriginalFileAfterIndex(indexed);
            bizMetrics.indexed(com.superprogrammer.common.metrics.BizMetrics.INDEX_SUCCESS);
            log.info("索引完成 nodeId={} kbId={} model={}", node.getId(), job.getKbId(), embeddingModel);
        } catch (Exception e) {
            log.error("索引 job 处理失败 jobId={}: {}", job.getId(), e.getMessage(), e);
            txService.failJob(job.getId(), truncate(e.getMessage(), MAX_ERROR_LEN));
            bizMetrics.indexed(com.superprogrammer.common.metrics.BizMetrics.INDEX_FAIL);
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
                bizMetrics.indexed(com.superprogrammer.common.metrics.BizMetrics.INDEX_VOID);
                return;
            }
            L1Metadata l1;
            try {
                l1 = objectMapper.readValue(doc.getL1Metadata(), L1Metadata.class);
            } catch (Exception e) {
                txService.voidJob(job.getId(), "L1 元数据解析失败，L1 job 作废: " + e.getMessage());
                bizMetrics.indexed(com.superprogrammer.common.metrics.BizMetrics.INDEX_VOID);
                return;
            }
            String text = L1EmbedText.build(l1);
            String l1Hash = HashUtil.sha256(text);
            if (!eq(l1Hash, job.getContentHash())) {
                txService.voidJob(job.getId(), "L1 元数据已变更，L1 job 作废（新版本 job 接管）");
                bizMetrics.indexed(com.superprogrammer.common.metrics.BizMetrics.INDEX_VOID);
                return;
            }
            if (text.isBlank()) {
                txService.voidJob(job.getId(), "L1 文本为空（summary/outline/rules 全空），L1 job 作废");
                bizMetrics.indexed(com.superprogrammer.common.metrics.BizMetrics.INDEX_VOID);
                return;
            }

            KnowledgeBase kb = knowledgeBaseService.ensure(job.getKbId());
            String embeddingModel = kb.getEmbeddingModel();

            // 计费归户：按文档上传者（doc.createdBy，doc 已在上方取出）归户 L1 embed 计费
            float[] vector = llmGateway.embed(text, embeddingModel, doc.getCreatedBy());
            if (vector.length != HalfVecUtil.DIM) {
                throw new RuntimeException("L1 embedding 维度不匹配 expected=" + HalfVecUtil.DIM
                        + " actual=" + vector.length);
            }
            String halfvec = HalfVecUtil.toHalfVec(vector);

            IndexJobTxService.IndexedDoc indexed = txService.completeUpsertL1(job.getId(), job.getDocumentId(), job.getKbId(),
                    embeddingModel, halfvec, l1Hash);
            cleanOriginalFileAfterIndex(indexed);
            bizMetrics.indexed(com.superprogrammer.common.metrics.BizMetrics.INDEX_SUCCESS);
            log.info("L1 索引完成 docId={} kbId={} model={}", job.getDocumentId(), job.getKbId(), embeddingModel);
        } catch (Exception e) {
            log.error("索引 job 处理失败 jobId={}: {}", job.getId(), e.getMessage(), e);
            txService.failJob(job.getId(), truncate(e.getMessage(), MAX_ERROR_LEN));
            bizMetrics.indexed(com.superprogrammer.common.metrics.BizMetrics.INDEX_FAIL);
        }
    }

    /**
     * D5 文件生命周期：文档转 INDEXED 时（completeUpsert/L1 返回非空 IndexedDoc）在事务外清原件字节。
     * 受 {@code app.files.retain-after-index} 控制（默认 false=清；调试可设 true 保留原件）。
     * IMAGE/FILE 文档**跳过清理**（原件是回显资产，必须保留）；其余 docType 照常清。
     * fileRef 为 null（未转换 / 无原件）→ 跳过。文件 IO 刻意在 DB 事务外：不阻塞 INDEXED 标记，删失败不回滚。
     */
    void cleanOriginalFileAfterIndex(IndexJobTxService.IndexedDoc indexed) {
        if (indexed == null || retainAfterIndex) {
            return;
        }
        String fileRef = indexed.fileRef();
        if (fileRef == null || fileRef.isBlank()) {
            return;
        }
        String dt = indexed.docType();
        if ("IMAGE".equals(dt) || "FILE".equals(dt)) {
            log.info("IMAGE/FILE 文档保留原件字节供回显 docId={} fileId={}", indexed.docId(), stripFileRef(fileRef));
            return;
        }
        String fileId = stripFileRef(fileRef);
        if (fileId == null || fileId.isBlank()) {
            return;
        }
        try {
            fileStorageService.cleanAfterIndex(fileId);
            log.info("文档 INDEXED 后清原件字节 fileId={}", fileId);
        } catch (Exception e) {
            // 清原件失败不阻断：知识完整性靠 nodes + 向量，不依赖原件；文档删除时 delete() 兜底
            log.warn("清原件字节失败 fileId={}: {}", fileId, e.getMessage());
        }
    }

    /** fileRef（/api/files/{fileId}）→ fileId。与 DocumentParserService/KnowledgeDocumentService 同款。 */
    private static String stripFileRef(String fileRef) {
        if (fileRef == null) {
            return null;
        }
        String prefix = "/api/files/";
        int idx = fileRef.indexOf(prefix);
        return idx >= 0 ? fileRef.substring(idx + prefix.length()) : fileRef;
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
