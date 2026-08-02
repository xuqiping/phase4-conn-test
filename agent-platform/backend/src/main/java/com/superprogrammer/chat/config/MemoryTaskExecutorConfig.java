package com.superprogrammer.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 记忆模块专用线程池（embed 并行 fan-out + ASYNC 记忆处理 orchestrator）。
 * <p>独立于 {@code knowledgeTaskExecutor}（KB 索引 core2/max4）：记忆 embed 更便宜 → 放大并发（core4/max8），
 * 且与 KB 索引互不争用。
 * <p><b>拒绝策略 AbortPolicy（绝不回退 servlet 线程）</b>：队列+池满时抛 {@link RejectedExecutionException}，
 * 由提交方捕获并记 incident（前端轮询弹窗）。此前用 CallerRunsPolicy，云上 LLM 慢→任务堆积→调用方 servlet
 * 线程亲自同步跑整段 processMemory（含多次 LLM 调用）→ Tomcat 池耗尽→死亡螺旋（见 RB-001 根因②）。
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
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
