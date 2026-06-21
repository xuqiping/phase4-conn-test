package com.superprogrammer.knowledge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 知识库解析/索引任务线程池。
 * LLM 调用阻塞且计费 → 收敛并发（core 2 / max 4）。
 * CallerRunsPolicy：队列满时由调用线程（已 commit 的上传请求线程）执行，不丢任务。
 */
@Configuration
public class KnowledgeTaskExecutorConfig {

    @Bean(name = "knowledgeTaskExecutor")
    public Executor knowledgeTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("kb-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
