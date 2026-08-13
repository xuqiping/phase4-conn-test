package com.superprogrammer.knowledge.event;

import com.superprogrammer.knowledge.config.VisibilityCacheProperties;
import com.superprogrammer.knowledge.service.VisibilitySetService;
import com.superprogrammer.knowledge.mapper.RagAnswerCacheMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 可见集失效（v6 §5.2，阶段4-A）。
 * grant/revoke/doc-delete 同事务发 {@link VisibilityInvalidationEvent}，
 * AFTER_COMMIT 异步 SCAN MATCH `vis:*:*:{kbId}` + 批量 DEL 该 KB 所有用户缓存 key。
 *
 * opsForValue 无 scan → 用 RedisCallback 拿原生 connection.scan（Cursor 必须 try-with-resources 关闭）。
 * 单实例 Phase1：SCAN 清本节点 Redis；多实例留 keyspace notification（非目标）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisibilityInvalidationListener {

    private final StringRedisTemplate redisTemplate;
    private final VisibilityCacheProperties props;
    private final RagAnswerCacheMapper answerCacheMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.superprogrammer.knowledge.opensearch.OpenSearchReconciliationService openSearchReconciliationService;

    @Async("knowledgeTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvalidate(VisibilityInvalidationEvent event) {
        if (event == null || event.getKbId() == null) {
            return;
        }
        invalidateKb(event.getKbId());
        try {
            int invalidated = answerCacheMapper.invalidateByKb(event.getKbId());
            log.info("答案缓存主动失效 kbId={} rows={}", event.getKbId(), invalidated);
        } catch (RuntimeException e) {
            log.warn("答案缓存主动失效失败 kbId={}: {}", event.getKbId(), e.getMessage());
        }
        if (openSearchReconciliationService != null) {
            try {
                openSearchReconciliationService.deleteKnowledgeBase(event.getKbId());
                log.info("OpenSearch 可见性副本已清除并等待重建 kbId={}", event.getKbId());
            } catch (Exception e) {
                log.warn("OpenSearch 可见性副本清除失败 kbId={} errorType={}",
                        event.getKbId(), e.getClass().getSimpleName());
            }
        }
    }

    public void invalidateKb(Long kbId) {
        if (kbId == null) {
            return;
        }
        String pattern = VisibilitySetService.kbKeyPattern(kbId);
        ScanOptions opts = ScanOptions.scanOptions().match(pattern).count(props.getScanCount()).build();
        try {
            redisTemplate.execute((RedisCallback<Void>) conn -> {
                List<String> batch = new ArrayList<>(props.getScanCount());
                try (Cursor<byte[]> cursor = conn.scan(opts)) {
                    while (cursor.hasNext()) {
                        batch.add(new String(cursor.next(), StandardCharsets.UTF_8));
                        if (batch.size() >= props.getScanCount()) {
                            deleteBatch(batch);
                            batch.clear();
                        }
                    }
                } catch (Exception io) {
                    log.warn("可见集 SCAN cursor 异常 kbId={}: {}", kbId, io.getMessage());
                }
                if (!batch.isEmpty()) {
                    deleteBatch(batch);
                }
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("可见集失效失败 kbId={}: {}", kbId, e.getMessage());
        }
    }

    private void deleteBatch(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        Long n = redisTemplate.delete(keys);
        log.info("可见集缓存失效 删 key={} 条", n);
    }
}
