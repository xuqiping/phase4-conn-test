package com.superprogrammer.common.audit;

import com.superprogrammer.common.logging.CompositeTaskDecorator;
import com.superprogrammer.common.logging.MdcContextTaskDecorator;
import com.superprogrammer.billing.context.BillingContextTaskDecorator;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计异步落库服务（日志系统 LOG-FR-10）：调用线程提交即返，绝不阻断业务主流程。
 *
 * <p>专用小池（core1/max2/queue500，AbortPolicy 由 submit 处捕获）：审计写库失败/队列满
 * → 仅 WARN + 丢弃计数，<b>绝不外抛</b>（安全检查清单 / 联动点：失败期间审计缺失由 P3 告警兜底）。
 * <p>MDC 快照经 {@link MdcContextTaskDecorator} 透传：池线程插入前的日志仍带原请求 traceId。
 */
@Slf4j
@Service
public class AuditLogService {

    private final AuditHashChainService hashChainService;
    private final Executor auditTaskExecutor;

    /** 落库失败/队列满丢弃累计数（可观测性：WARN 日志带此计数）。 */
    private final AtomicLong droppedCount = new AtomicLong();

    public AuditLogService(AuditHashChainService hashChainService, Executor auditTaskExecutor) {
        this.hashChainService = hashChainService;
        this.auditTaskExecutor = auditTaskExecutor;
    }

    /** 异步落一条审计行。fire-and-forget：任何失败只 WARN 计数。 */
    public void record(AuditLogEntity row) {
        try {
            auditTaskExecutor.execute(() -> {
                try {
                    // 安全体系 S2 D1（SEC-FR-040）：insert 收敛到链式咽喉点（advisory 锁+哈希链）
                    hashChainService.insertChained(row);
                } catch (Exception e) {
                    log.warn("审计落库失败(已丢) module={} action={} dropped={} : {}",
                            row.getModule(), row.getAction(), droppedCount.incrementAndGet(), e.toString());
                }
            });
        } catch (Exception e) {
            log.warn("审计队列满(已丢) module={} action={} dropped={} : {}",
                    row.getModule(), row.getAction(), droppedCount.incrementAndGet(), e.toString());
        }
    }

    /** 从当前 MDC 取上下文建行（traceId/userId/username/clientIp 由 MdcUserFilter 放入）。 */
    public AuditLogEntity fromMdc(String module, String action, String targetType, String targetId,
                                  String detailJson, String result) {
        AuditLogEntity row = new AuditLogEntity();
        row.setTraceId(MDC.get("traceId"));
        row.setClientIp(MDC.get("clientIp"));
        String userId = MDC.get("userId");
        if (userId != null && !"-".equals(userId)) {
            try {
                row.setUserId(Long.parseLong(userId));
            } catch (NumberFormatException ignored) {
                // userId 非数字（异常态）→ 留 NULL，不阻断审计
            }
        }
        row.setUsername(MDC.get("username"));
        row.setModule(module);
        row.setAction(action);
        row.setTargetType(targetType == null || targetType.isEmpty() ? null : targetType);
        row.setTargetId(targetId);
        row.setDetailJson(detailJson);
        row.setResult(result);
        return row;
    }

    /** 审计线程池（独立小池，不与记忆/KB/媒体争用；AbortPolicy 由 record 捕获降级）。 */
    @Configuration
    static class AuditExecutorConfig {
        @Bean(name = "auditTaskExecutor")
        Executor auditTaskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(2);
            executor.setQueueCapacity(500);
            executor.setThreadNamePrefix("audit-task-");
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
            // MDC 快照（traceId 不断链）+ 计费上下文（与四大池同规约）
            executor.setTaskDecorator(new CompositeTaskDecorator(
                    new MdcContextTaskDecorator(), new BillingContextTaskDecorator()));
            executor.setWaitForTasksToCompleteOnShutdown(true);
            executor.setAwaitTerminationSeconds(10);
            executor.initialize();
            return executor;
        }
    }
}
