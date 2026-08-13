// agent-platform/backend/src/main/java/com/superprogrammer/common/config/SecurityTaskExecutorConfig.java
package com.superprogrammer.common.config;

import com.superprogrammer.common.logging.CompositeTaskDecorator;
import com.superprogrammer.common.logging.MdcContextTaskDecorator;
import com.superprogrammer.common.metrics.BizMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 安全监控专用线程池（11x 加固 · P3-C8）：SecurityMonitorWorker 消费 ApplicationSecurityEvent。
 *
 * <p>小池（core1/max2/queue1000）与审计/记忆/媒体池隔离——安全事件洪峰不挤占别域。
 * 拒绝策略：记 WARN + securityEventDropped 指标后吞（不阻事件发布方主链；事件丢失可接受）。
 * TaskDecorator 透传 MDC（traceId 不断链，复用日志系统 CompositeTaskDecorator 范式）。</p>
 */
@Slf4j
@Configuration
public class SecurityTaskExecutorConfig {

    @Bean(name = "securityTaskExecutor")
    public ThreadPoolTaskExecutor securityTaskExecutor(BizMetrics bizMetrics) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("security-monitor-");
        executor.setTaskDecorator(new CompositeTaskDecorator(new MdcContextTaskDecorator()));
        executor.setRejectedExecutionHandler(new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                // 队列满：吞事件 + 计数（发布方不感知，主链不阻）
                log.warn("安全事件队列满(丢弃本事件) active={} queue={}", e.getActiveCount(), e.getQueue().size());
                bizMetrics.securityEventDropped();
            }
        });
        executor.initialize();
        return executor;
    }
}
