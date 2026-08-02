package com.superprogrammer.knowledge.service.internal;

import com.superprogrammer.knowledge.entity.KnowledgeIndexJob;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.entity.KnowledgeReconciliationReport;
import com.superprogrammer.knowledge.mapper.KnowledgeEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeIndexJobMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeReconciliationReportMapper;
import com.superprogrammer.knowledge.mapper.RagAnswerCacheMapper;
import com.superprogrammer.knowledge.mapper.RagMemoryFactMapper;
import com.superprogrammer.knowledge.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 对账/清理的 DB 写操作（阶段7 ReconciliationJob）。
 * 镜像 {@link IndexJobTxService}：独立 bean，每法 @Transactional 短事务，无 LLM 调用。
 *
 * 范围（report-only + decay/orphan purge）：
 *   scanKb              — 计 total/drift/orphan/dead，插一行 knowledge_reconciliation_reports
 *   purgeDecayedAnswerCache — 批量硬删 rag_answer_cache decay 过期 ACTIVE 行
 *   purgeOrphanEmbeddings   — 删 KB 下孤儿向量（node 软删/ARCHIVED）
 *   enqueueReindexJobs      — seam（autoRepair=false 默认不调；claimBatch 暂不消费 REINDEX，见 javadoc）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationTxService {

    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeIndexJobMapper indexJobMapper;
    private final KnowledgeEmbeddingMapper embeddingMapper;
    private final RagAnswerCacheMapper answerCacheMapper;
    private final RagMemoryFactMapper memoryFactMapper;
    private final KnowledgeReconciliationReportMapper reportMapper;

    /**
     * 扫一个 KB：读一致性窗口内取各项计数，落一行报告。drift 列表返回供可选修复（但默认仅记）。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeReconciliationReport scanKb(Long kbId) {
        long total = nn(nodeMapper.countActiveByKb(kbId));
        List<Long> drifted = indexJobMapper.findDriftedNodeIds(kbId);
        long orphan = nn(indexJobMapper.countOrphanEmbeddings(kbId));
        long deadFailed = nn(indexJobMapper.countDeadFailedByKb(kbId));
        long stuck = nn(indexJobMapper.countStuckRunningByKb(kbId));

        KnowledgeReconciliationReport r = new KnowledgeReconciliationReport();
        r.setKbId(kbId);
        r.setScannedAt(OffsetDateTime.now());
        r.setTotalNodes((int) total);
        r.setDriftCount(drifted.size());
        r.setOrphanCount((int) orphan);
        r.setStaleWithEmbedding(0);   // Phase1 无 STALE 节点态，占位 0（后续节点状态机扩展时填）
        r.setRepairedCount(0);         // scan 阶段不修复
        r.setDeadJobCount((int) (deadFailed + stuck));
        r.setCreatedAt(OffsetDateTime.now());
        reportMapper.insert(r);
        log.info("KB {} 对账完成: total={} drift={} orphan={} dead={}",
                kbId, total, drifted.size(), orphan, r.getDeadJobCount());
        return r;
    }

    /**
     * 批量硬删 answer_cache decay 过期行，循环至无剩余或达 maxBatches。返回总删除数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int purgeDecayedAnswerCache(int batchSize, int maxBatches) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int deleted = answerCacheMapper.deleteDecayed(batchSize);
            if (deleted <= 0) {
                break;
            }
            total += deleted;
            if (deleted < batchSize) {
                break;   // 末批不足，无更多
            }
        }
        return total;
    }

    /** 删 KB 下孤儿向量（node 软删/ARCHIVED），返回删除数。 */
    @Transactional(rollbackFor = Exception.class)
    public int purgeOrphanEmbeddings(Long kbId) {
        int deleted = embeddingMapper.deleteOrphansByKb(kbId);
        if (deleted > 0) {
            log.info("KB {} 清理孤儿向量 {} 行", kbId, deleted);
        }
        return deleted;
    }

    /**
     * 批量硬删 rag_memory_facts decay 过期行（sibling purge，对齐 {@link #purgeDecayedAnswerCache}）。
     * 当前无生产者写该表（M2 软提示特性未启用），调用通常返回 0；接口就位供将来启用时无需再补对账路径。
     */
    @Transactional(rollbackFor = Exception.class)
    public int purgeDecayedMemoryFacts(int batchSize, int maxBatches) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int deleted = memoryFactMapper.deleteDecayed(batchSize);
            if (deleted <= 0) {
                break;
            }
            total += deleted;
            if (deleted < batchSize) {
                break;   // 末批不足，无更多
            }
        }
        return total;
    }

    /**
     * drift 修复：为漂移 node 入 REINDEX job（content_hash=node 当前值，claimBatch 现消费 REINDEX，
     * worker 重嵌 node.content + upsert → 向量 hash 对齐 node，drift 消除）。
     * 幂等：idempotency_key=sha256(nodeId:contentHash:REINDEX) UNIQUE，ON CONFLICT DO NOTHING，
     * 同 node+hash 已 PENDING/RUNNING/DONE 则跳过；node 再变更 → 新 hash → 新 key → 新 job（正确）。
     * node 读为 null/非 ACTIVE（embed 失活）→ 跳过（不下 job）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int enqueueReindexJobs(List<Long> driftedNodeIds, Long kbId) {
        if (driftedNodeIds == null || driftedNodeIds.isEmpty()) {
            return 0;
        }
        int enqueued = 0;
        for (Long nodeId : driftedNodeIds) {
            KnowledgeNode node = nodeMapper.selectById(nodeId);
            if (node == null || !"ACTIVE".equals(node.getStatus())) {
                continue;   // 已失活/删除（@TableLogic 已滤软删），不下 job
            }
            KnowledgeIndexJob j = new KnowledgeIndexJob();
            j.setNodeId(nodeId);
            j.setKbId(kbId);
            j.setJobType("REINDEX");
            j.setContentHash(node.getContentHash());
            j.setIdempotencyKey(HashUtil.sha256(nodeId + ":" + node.getContentHash() + ":REINDEX"));
            enqueued += indexJobMapper.insertReindexJobIgnoreConflict(j);
        }
        return enqueued;
    }

    /**
     * 单 KB drift 修复入口（autoRepair=true 时由 {@code ReconciliationWorker.scanBatch} 调）：
     * 读 drifted node_ids → {@link #enqueueReindexJobs}。返回新入队 REINDEX job 数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int repairDrift(Long kbId) {
        List<Long> drifted = indexJobMapper.findDriftedNodeIds(kbId);
        if (drifted.isEmpty()) {
            return 0;
        }
        int n = enqueueReindexJobs(drifted, kbId);
        log.info("KB {} drift 修复：入 REINDEX job {} 条（drift {} 个）", kbId, n, drifted.size());
        return n;
    }

    private static long nn(Long v) {
        return v == null ? 0L : v;
    }
}
