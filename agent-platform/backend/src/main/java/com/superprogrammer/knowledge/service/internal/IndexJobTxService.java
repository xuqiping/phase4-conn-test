package com.superprogrammer.knowledge.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeIndexJob;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeIndexJobMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 索引 job 的所有 DB 写操作（v6 §6/§7.3.2，阶段2 第4项）。
 * 独立 bean：IndexJobWorker（非事务）跨 bean 调本类的 @Transactional 方法，经 Spring 代理生效
 * （同类自调绕代理的坑，见 KnowledgeNodeWriter 说明）。
 *
 * 事务粒度刻意小：claim / complete / void / fail 各自独立短事务，LLM embed 调用必须在事务外
 * （秒级阻塞+计费，不能占着 DB 连接/事务）。
 *
 * 不变式落地：
 *   I2 — claim 后由 worker 读 node 再校 hash/status/deleted；complete 内 tx 再读 node 复校（防 embed 期间变更）
 *   I1 — upsert 写 content_hash=node.content_hash；complete 内校 content_hash 一致才写
 *   I4 — 幂等：job.idempotency_key 唯一（writer 保证）+ embedding.node_id 唯一 ON CONFLICT 就地覆盖
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexJobTxService {

    private static final Long TENANT_ID = 1L;
    private static final int LOCK_MINUTES = 5;
    private static final long BACKOFF_BASE_SEC = 10;
    private static final long BACKOFF_CAP_SEC = 300;
    private static final String CONTEXT_HASH = "__phase1_placeholder__";

    private final KnowledgeIndexJobMapper indexJobMapper;
    private final KnowledgeEmbeddingMapper embeddingMapper;
    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeDocumentMapper documentMapper;

    /**
     * 认领一批待处理 job（FOR UPDATE SKIP LOCKED，多 worker 安全）。
     * 认领即置 RUNNING + attempt+1 + lockedUntil，返回内存实体供 worker 异步处理。
     * 认领条件：UPSERT 或 REINDEX 类型（两者处理同：重嵌 node.content + upsert 向量，
     * REINDEX 的 content_hash=node 当前值，drift 修复）+ (PENDING 或 RUNNING 过期) + 锁过期/无锁。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<KnowledgeIndexJob> claimBatch(int limit) {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaQueryWrapper<KnowledgeIndexJob> w = new LambdaQueryWrapper<>();
        w.and(q -> q.eq(KnowledgeIndexJob::getStatus, "PENDING")
                        .or().eq(KnowledgeIndexJob::getStatus, "RUNNING"))
                .in(KnowledgeIndexJob::getJobType, List.of("UPSERT", "REINDEX"))
                .and(q -> q.isNull(KnowledgeIndexJob::getLockedUntil)
                        .or().lt(KnowledgeIndexJob::getLockedUntil, now))
                .last("LIMIT " + limit + " FOR UPDATE SKIP LOCKED");
        List<KnowledgeIndexJob> jobs = indexJobMapper.selectList(w);
        if (jobs.isEmpty()) {
            return List.of();
        }
        OffsetDateTime lockUntil = OffsetDateTime.now().plusMinutes(LOCK_MINUTES);
        for (KnowledgeIndexJob j : jobs) {
            int attempt = (j.getAttempt() == null ? 0 : j.getAttempt()) + 1;
            j.setStatus("RUNNING");
            j.setAttempt(attempt);
            j.setLockedUntil(lockUntil);
            indexJobMapper.updateById(j);
        }
        return jobs;
    }

    /**
     * 完成一个 UPSERT job：tx 内复校 node（I1/I2）→ upsert 向量 → job DONE → 文档可能 INDEXED。
     * contentHash = 写入向量时所依据的 node.content_hash（worker 在 embed 前读到）。
     * 若 tx 内复校发现 node 已变更/失活 → 转作 voidJob（新版本 job 接管，本 job 作废）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void completeUpsert(Long jobId, Long nodeId, Long documentId, Long kbId,
                               String embeddingModel, String halfvec, String contentHash) {
        KnowledgeNode node = nodeMapper.selectById(nodeId);
        if (node == null || !"ACTIVE".equals(node.getStatus())
                || !eq(node.getContentHash(), contentHash)) {
            voidJob(jobId, "完成前节点已变更/失活/删除，job 作废（新版本 job 接管）");
            return;
        }
        embeddingMapper.upsert(node.getId(), TENANT_ID, kbId, "L0", embeddingModel,
                halfvec, node.getContentHash(), CONTEXT_HASH);

        LambdaUpdateWrapper<KnowledgeIndexJob> ju = new LambdaUpdateWrapper<>();
        ju.eq(KnowledgeIndexJob::getId, jobId)
                .set(KnowledgeIndexJob::getStatus, "DONE")
                .set(KnowledgeIndexJob::getLockedUntil, null)
                .set(KnowledgeIndexJob::getLastError, null);
        indexJobMapper.update(null, ju);

        markDocIndexedIfDone(documentId);
    }

    /** I2 作废：节点变更/失活 → job FAILED 终态（新版本 job 接管，不可重跑本 job）。 */
    @Transactional(rollbackFor = Exception.class)
    public void voidJob(Long jobId, String reason) {
        LambdaUpdateWrapper<KnowledgeIndexJob> u = new LambdaUpdateWrapper<>();
        u.eq(KnowledgeIndexJob::getId, jobId)
                .set(KnowledgeIndexJob::getStatus, "FAILED")
                .set(KnowledgeIndexJob::getLockedUntil, null)
                .set(KnowledgeIndexJob::getLastError, reason);
        indexJobMapper.update(null, u);
    }

    /**
     * 处理异常：attempt 已在 claim 时 +1。
     * attempt >= maxAttempt → DEAD（终态）；否则 PENDING + 指数退避（locked_until=now+backoff）待重新认领。
     */
    @Transactional(rollbackFor = Exception.class)
    public void failJob(Long jobId, String error) {
        KnowledgeIndexJob j = indexJobMapper.selectById(jobId);
        if (j == null) {
            return;
        }
        int attempt = j.getAttempt() == null ? 0 : j.getAttempt();
        int max = j.getMaxAttempt() == null ? 5 : j.getMaxAttempt();
        boolean dead = attempt >= max;

        LambdaUpdateWrapper<KnowledgeIndexJob> u = new LambdaUpdateWrapper<>();
        u.eq(KnowledgeIndexJob::getId, jobId).set(KnowledgeIndexJob::getLastError, error);
        if (dead) {
            u.set(KnowledgeIndexJob::getStatus, "DEAD")
                    .set(KnowledgeIndexJob::getLockedUntil, null);
            log.warn("索引 job 达到 max_attempt → DEAD jobId={} attempt={}", jobId, attempt);
        } else {
            long shift = Math.max(0, attempt - 1);
            long backoff = Math.min(BACKOFF_BASE_SEC << shift, BACKOFF_CAP_SEC);
            u.set(KnowledgeIndexJob::getStatus, "PENDING")
                    .set(KnowledgeIndexJob::getLockedUntil, OffsetDateTime.now().plusSeconds(backoff));
        }
        indexJobMapper.update(null, u);
    }

    /** 文档下无 PENDING/RUNNING job → 置 INDEXED（DEAD 容忍：有缺口但其余已索引）。 */
    private void markDocIndexedIfDone(Long docId) {
        if (docId == null) {
            return;
        }
        Long remaining = indexJobMapper.countPendingRunningByDoc(docId);
        if (remaining != null && remaining == 0) {
            LambdaUpdateWrapper<KnowledgeDocument> du = new LambdaUpdateWrapper<>();
            du.eq(KnowledgeDocument::getId, docId).set(KnowledgeDocument::getStatus, "INDEXED");
            documentMapper.update(null, du);
            log.info("文档全部 job 完成 → INDEXED docId={}", docId);
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
