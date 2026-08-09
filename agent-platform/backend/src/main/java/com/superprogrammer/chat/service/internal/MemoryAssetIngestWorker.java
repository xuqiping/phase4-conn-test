package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.mapper.MemoryAssetMemoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 文件 ingestion 定时 worker（V69 二期 P3 Step 2，FR-202）。
 * <p>
 * 类 {@link MemoryConsolidationWorker} 轮询认领范式，但认领走<b>条件 UPDATE 占位</b>
 * （P2 状态机同款：{@code WHERE ingest_status='PROCESSING' AND locked_until 过期}，
 * 影响行数=0 即被他节点抢走）——双节点不双跑，无需 FOR UPDATE SKIP LOCKED。
 * <p>
 * <b>崩溃自愈</b>：认领置 {@code locked_until=now+10min}，节点崩了锁过期后他节点自然再认领。
 * <b>配置开关</b>（运维考量）：{@code memory.asset.ingest.enabled}=false 全停 ingestion
 * （已上传的行留 PROCESSING，开回后续跑，不影响聊天）。
 */
@Slf4j
@Component
public class MemoryAssetIngestWorker {

    private static final int BATCH = 5;
    private static final int LOCK_MINUTES = 10;

    private final boolean enabled;
    private final MemoryAssetMemoryMapper memoryMapper;
    private final MemoryAssetIngestService ingestService;

    public MemoryAssetIngestWorker(@Value("${memory.asset.ingest.enabled:true}") boolean enabled,
                                   MemoryAssetMemoryMapper memoryMapper,
                                   MemoryAssetIngestService ingestService) {
        this.enabled = enabled;
        this.memoryMapper = memoryMapper;
        this.ingestService = ingestService;
    }

    /** 轮询认领 PROCESSING 文件记忆（默认 60s；单批 5 条防单轮 LLM 风暴）。 */
    @Scheduled(fixedDelayString = "${memory.asset.ingest.poll-ms:60000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<Long> candidates;
        try {
            candidates = memoryMapper.findClaimCandidates(now, BATCH);
        } catch (Exception e) {
            log.error("ingestion worker 认领查询失败: {}", e.getMessage(), e);
            return;
        }
        for (Long id : candidates) {
            try {
                if (memoryMapper.claim(id, now, now.plusMinutes(LOCK_MINUTES)) == 0) {
                    continue;   // 被他节点抢走/状态已前移
                }
                ingestService.processOne(id);
            } catch (Exception e) {
                // processOne 内部已兜状态；此处仅兜意外异常防整批中断
                log.error("ingestion worker 处理异常 memoryId={}: {}", id, e.getMessage(), e);
            }
        }
    }
}
