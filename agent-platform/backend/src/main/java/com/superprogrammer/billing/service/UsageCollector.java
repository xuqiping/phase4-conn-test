package com.superprogrammer.billing.service;

import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.billing.mapper.LlmUsageLogMapper;
import com.superprogrammer.common.logging.MdcContextTaskDecorator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 用量采集（异步、可丢）——spec §5 决策1 的「采集写路径」。
 * <p>与扣减（{@link PointsWalletService} 同步事务）严格分离：
 * <ul>
 *   <li>本服务=异步落 {@code llm_usage_logs}（admin 看 token/¥/积分审计），<b>fire-and-forget</b>：
 *       调用线程提交即返；DB 写失败 / 队列满 → 降级丢一条 + warn，<b>绝不</b>把异常抛回业务线程。</li>
 *   <li>扣减=同步行锁（见 wallet.charge），不可丢——两路径独立，采集挂了不影响扣减对账。</li>
 * </ul>
 * <p>专用线程池（2 线程 + 有界队列 1000）：与 chat/embed 反应式线程隔离，
 * 突发流量超队列直接丢（AbortPolicy 捕获），不 OOM、不阻塞调用线程。
 * <p>{@code billing.enabled=false} 时 record 短路（不采不扣）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageCollector {

    private final LlmUsageLogMapper usageLogMapper;

    /** 计费总闸。关则不采集（与扣减同开关）。 */
    @Value("${billing.enabled:true}")
    private boolean enabled;

    /** 采集线程数。usage 日志写入轻量，2 线程足够；过多反而争 DB 连接。 */
    private static final int POOL_SIZE = 2;
    /** 待写队列上限。突发超此即丢（采集可丢，spec §5）。 */
    private static final int QUEUE_CAPACITY = 1000;

    private ExecutorService pool;

    @PostConstruct
    void init() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "usage-log-" + counter.incrementAndGet());
                t.setDaemon(true); // 不阻塞 JVM 退出（@PreDestroy 另做优雅 drain）
                return t;
            }
        };
        // 有界队列 + AbortPolicy：队列满抛 RejectedExecutionException，submit 处捕获→丢+warn
        pool = new ThreadPoolExecutor(POOL_SIZE, POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), factory, new ThreadPoolExecutor.AbortPolicy());
        log.info("UsageCollector 采集池就绪 threads={} queue={}", POOL_SIZE, QUEUE_CAPACITY);
    }

    @PreDestroy
    void shutdown() {
        if (pool == null) return;
        pool.shutdown();
        try {
            // 给在途写入库最多 3s；采集可丢，不长时间拖停机
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("UsageCollector 采集池已关停");
    }

    /**
     * 异步落一条 usage 审计日志。fire-and-forget：调用线程提交后立即返回。
     * <p>enabled=false 短路。costYuan/pointsConsumed 由调用方（gateway 出口）先用
     * {@link PricingService}+{@link PointsRatioService} 算好传入（采集与扣减共用同一组计算值）。
     *
     * @param status   {@link LlmUsageLogEntity#STATUS_SUCCESS}/FAILED/ESTIMATED
     * @param errorMsg 失败原因（成功为 null）
     */
    public void record(Long userId, Long providerId, String providerScope, String model, String kind,
                       Integer tokensInput, Integer tokensOutput,
                       BigDecimal costYuan, BigDecimal pointsConsumed,
                       String status, String errorMsg) {
        if (!enabled) {
            return;
        }
        LlmUsageLogEntity row = new LlmUsageLogEntity();
        row.setCreatedAt(OffsetDateTime.now());
        row.setUserId(userId);
        row.setProviderId(providerId);
        row.setProviderScope(providerScope);
        row.setModel(model);
        row.setKind(kind);
        row.setTokensInput(tokensInput);
        row.setTokensOutput(tokensOutput);
        row.setCostYuan(costYuan);
        row.setPointsConsumed(pointsConsumed);
        row.setStatus(status != null ? status : LlmUsageLogEntity.STATUS_SUCCESS);
        row.setErrorMsg(errorMsg);
        submit(row);
    }

    /** 提交一行入库；队列满 / 写失败均降级 warn，绝不抛回调用线程。 */
    private void submit(LlmUsageLogEntity row) {
        try {
            // 日志系统 LOG-FR-02：原始 ExecutorService 无 TaskDecorator，submit 处手工包 MDC 快照（traceId 不断链）
            pool.submit(MdcContextTaskDecorator.wrap(() -> {
                try {
                    usageLogMapper.insert(row);
                } catch (Exception e) {
                    // 采集可丢：单条写失败不影响扣减对账，仅 warn
                    log.warn("usage 日志写入失败(已丢) userId={} model={} kind={} : {}",
                            row.getUserId(), row.getModel(), row.getKind(), e.toString());
                }
            }));
        } catch (Exception e) {
            // RejectedExecutionException（队列满）等提交期异常 → 丢一条 + warn
            log.warn("usage 采集队列满(已丢) userId={} model={} : {}", row.getUserId(), row.getModel(), e.toString());
        }
    }
}
