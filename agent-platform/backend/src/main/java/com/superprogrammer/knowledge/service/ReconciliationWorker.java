package com.superprogrammer.knowledge.service;

import com.superprogrammer.knowledge.config.ReconciliationProperties;
import com.superprogrammer.knowledge.entity.KnowledgeReconciliationReport;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseMapper;
import com.superprogrammer.knowledge.service.internal.ReconciliationTxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 对账 + decay 清理 worker（阶段7，v6 §7.3.6 最小对账 + §8.9a decay 兜底）。
 * 镜像 {@link IndexJobWorker}：@Component 无 @Transactional，DB 写经 {@link ReconciliationTxService} 代理。
 *
 * poll 流程：!enabled → 返回；分批扫 ACTIVE KB → 每批交 reconciliationTaskExecutor 异步 scanKb + 落报告
 *   （autoRepair=true 时 enqueue REINDEX，默认关）；全局批量清 answer_cache decay 行。
 * 异常吞（log.error，不崩 scheduler）。
 *
 * 独立 reconciliationTaskExecutor（core1/max2）：不与 knowledgeTaskExecutor（embed 计费阻塞 core2/max4）争用。
 */
@Slf4j
@Component
public class ReconciliationWorker {

    private final ReconciliationTxService txService;
    private final KnowledgeBaseMapper kbMapper;
    private final ReconciliationProperties props;
    private final Executor executor;

    public ReconciliationWorker(ReconciliationTxService txService,
                                KnowledgeBaseMapper kbMapper,
                                ReconciliationProperties props,
                                @Qualifier("reconciliationTaskExecutor") Executor executor) {
        this.txService = txService;
        this.kbMapper = kbMapper;
        this.props = props;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${rag.reconciliation.poll-ms:600000}")
    public void poll() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            // 1. 分批扫所有 ACTIVE KB
            int offset = 0;
            int batch = props.getKbBatch();
            int scanned = 0;
            while (true) {
                List<Long> kbIds = kbMapper.listActiveKbIds(batch, offset);
                if (kbIds == null || kbIds.isEmpty()) {
                    break;
                }
                List<Long> chunk = List.copyOf(kbIds);
                executor.execute(() -> scanBatch(chunk));
                scanned += kbIds.size();
                if (kbIds.size() < batch) {
                    break;   // 末批不足，无更多
                }
                offset += batch;
            }
            // 2. 全局清 answer_cache decay 行（跨 KB per-user，单列扫）
            int purged = txService.purgeDecayedAnswerCache(props.getDecayBatch(), 100);
            if (purged > 0) {
                log.info("answer_cache 清理 decayed rows: {}", purged);
            }
            // 3. 全局清 rag_memory_facts decay 行（sibling purge，M2 软提示；当前无生产者→通常 0）
            int purgedFacts = txService.purgeDecayedMemoryFacts(props.getDecayBatch(), 100);
            if (purgedFacts > 0) {
                log.info("memory_facts 清理 decayed rows: {}", purgedFacts);
            }
            if (scanned > 0) {
                log.info("Reconciliation 轮询完成，扫描 KB {} 个", scanned);
            }
        } catch (Exception e) {
            log.error("Reconciliation 轮询失败: {}", e.getMessage(), e);
        }
    }

    private void scanBatch(List<Long> kbIds) {
        for (Long kbId : kbIds) {
            try {
                KnowledgeReconciliationReport r = txService.scanKb(kbId);
                if (r.getOrphanCount() > 0) {
                    txService.purgeOrphanEmbeddings(kbId);
                }
                // autoRepair=true → drift 入 REINDEX job（claimBatch 现消费 REINDEX，worker 重嵌修复）。
                // 默认 autoRepair=false 仅报告 drift_count；启用前确认能承担 re-embed 的 LLM 计费。
                if (props.isAutoRepair() && r.getDriftCount() > 0) {
                    int fixed = txService.repairDrift(kbId);
                    if (fixed > 0) {
                        log.info("KB {} drift 修复入队 {} 个 REINDEX job", kbId, fixed);
                    }
                }
            } catch (Exception e) {
                log.error("KB {} 对账失败: {}", kbId, e.getMessage(), e);
            }
        }
    }
}
