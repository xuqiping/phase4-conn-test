package com.superprogrammer.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 记忆模块专用线程池（embed 并行 fan-out + ASYNC 记忆处理 orchestrator）。
 * <p>独立于 {@code knowledgeTaskExecutor}（KB 索引 core2/max4）：记忆 embed 更便宜 → 放大并发（core4/max8），
 * 且与 KB 索引互不争用。CallerRunsPolicy：队列满时调用线程执行，不丢任务。
 */
@Configuration
public class MemoryTaskExecutorConfig {

    @Bean(name = "memoryTaskExecutor")
    public Executor memoryTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mem-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
