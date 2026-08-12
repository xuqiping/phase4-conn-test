package com.superprogrammer.chat.config;

import com.superprogrammer.billing.context.BillingContextTaskDecorator;
import com.superprogrammer.common.logging.CompositeTaskDecorator;
import com.superprogrammer.common.logging.MdcContextTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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

    // 返回类型收窄 ThreadPoolTaskExecutor（非 Executor）：TaskExecutor 类型注入须能按声明类型命中本 bean
    // （审计池 auditTaskExecutor 出现后裸 TaskExecutor 注入会 2 选 1 歧义，MemoryRouting/GenerationService 已 @Qualifier 到本池）
    @Bean(name = "memoryTaskExecutor")
    public ThreadPoolTaskExecutor memoryTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mem-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 计费归户：透传提交线程 userId，记忆 embed/LLM 调用自动计费
        // 日志系统 LOG-FR-02：MDC 快照透传（traceId/userId 异步不断链），与计费上下文组合
        executor.setTaskDecorator(new CompositeTaskDecorator(
                new MdcContextTaskDecorator(), new BillingContextTaskDecorator()));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
