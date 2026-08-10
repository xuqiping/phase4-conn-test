package com.superprogrammer.common.logging;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * MDC 上下文透传装饰器（日志系统 LOG-FR-02）：@Async / 线程池任务不断链。
 *
 * <p>提交线程快照 MDC（traceId/spanId/userId 全在里面）→ 池线程执行前恢复 → finally {@code MDC.clear()}。
 * 无此装饰器时池线程 MDC 为空（ThreadLocal 不跨线程），异步日志 traceId 断链、userId 串号。
 *
 * <p>与 {@code BillingContextTaskDecorator} 通过 {@link CompositeTaskDecorator} 组合使用：
 * {@code executor.setTaskDecorator(new CompositeTaskDecorator(new MdcContextTaskDecorator(), new BillingContextTaskDecorator()))}
 *
 * <p><b>评审 checklist</b>：新增自定义线程池 / 手工 submit 的异步点，必须过本装饰器（或 {@link #wrap}）。
 */
public class MdcContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return wrap(runnable);
    }

    /** 手工 ExecutorService（非 ThreadPoolTaskExecutor）的 submit 处包装用。 */
    public static Runnable wrap(Runnable runnable) {
        // 提交线程（请求线程）捕获 MDC 快照
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        return () -> {
            if (snapshot != null) {
                MDC.setContextMap(snapshot);
            } else {
                MDC.clear();
            }
            try {
                runnable.run();
            } finally {
                // 池线程复用，跑完必清，防上下文串到下一个任务
                MDC.clear();
            }
        };
    }
}
