package com.superprogrammer.knowledge.config;

import com.superprogrammer.billing.context.BillingContextTaskDecorator;
import com.superprogrammer.common.logging.CompositeTaskDecorator;
import com.superprogrammer.common.logging.MdcContextTaskDecorator;
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
        // 计费归户：把提交线程（上传请求）的 userId 透传给 kb-task-* 线程，文档解析/索引 LLM 调用自动计费
        // 日志系统 LOG-FR-02：MDC 快照透传（traceId/userId 异步不断链），与计费上下文组合
        executor.setTaskDecorator(new CompositeTaskDecorator(
                new MdcContextTaskDecorator(), new BillingContextTaskDecorator()));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
