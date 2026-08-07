package com.superprogrammer.knowledge.config;

import com.superprogrammer.billing.context.BillingContextTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 对账/decay 清理专用线程池（阶段7 ReconciliationJob）。
 * <p>独立于 {@code knowledgeTaskExecutor}（core2/max4 为 LLM embed 计费阻塞专用）：
 * 对账是低优先级 DB 卫生工作，不可抢占 embed 管线（队列满 CallerRunsPolicy 会让 scheduler 线程跑阻塞活，拖垮 embed 轮询）。
 * 对账顺序扫描、DB bound → core1/max2 足够。
 */
@Configuration
public class ReconciliationTaskExecutorConfig {

    @Bean(name = "reconciliationTaskExecutor")
    public Executor reconciliationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("kb-recon-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 计费归户：透传提交线程 userId（对账本身不调 LLM，但 repairDrift 触发的 reindex 由此继承用户）
        executor.setTaskDecorator(new BillingContextTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
