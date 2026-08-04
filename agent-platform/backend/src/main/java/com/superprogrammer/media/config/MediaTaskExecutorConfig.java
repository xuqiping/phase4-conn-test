package com.superprogrammer.media.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 媒体生成任务线程池（独立于 chat/RAG/memory，零互饿）。
 *
 * <p>视频生成阻塞且计费（单任务分钟级）→ 收敛并发 core2/max4，队列 100。
 * {@link ThreadPoolExecutor.AbortPolicy}：队列满抛 RejectedExecutionException（由调用方决策，
 * 不退回 Tomcat 线程悄悄跑），与 plan「池满不抛回主线程」一致——submit 时 catch 转 503/限流话术。
 */
@Configuration
public class MediaTaskExecutorConfig {

    @Bean(name = "mediaTaskExecutor")
    public Executor mediaTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("media-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
