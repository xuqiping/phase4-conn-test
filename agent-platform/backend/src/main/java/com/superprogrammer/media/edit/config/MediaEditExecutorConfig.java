package com.superprogrammer.media.edit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 视频剪辑渲染线程池（独立于 media 生成/chat/RAG/memory，零互饿）。
 *
 * <p>FFmpeg 渲染 CPU 密集且阻塞（分钟级）→ 收敛并发 core2/max4，队列 20（剪辑比生成更重，队列更短快速失败）。
 * {@link ThreadPoolExecutor.AbortPolicy}：队列满抛 RejectedExecutionException（worker 记 failed 不退回 poll 线程悄悄跑）。
 */
@Configuration
public class MediaEditExecutorConfig {

    @Bean(name = "mediaEditExecutor")
    public Executor mediaEditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("media-edit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
