package com.superprogrammer.common.logging;

import org.springframework.core.task.TaskDecorator;

import java.util.Arrays;
import java.util.List;

/**
 * TaskDecorator 组合器（日志系统 LOG-FR-02）：{@code ThreadPoolTaskExecutor} 只收单个 TaskDecorator，
 * 而本项目异步任务需同时透传 MDC（traceId/userId）+ BillingContext（计费归户）。
 * 按声明顺序嵌套：{@code decorators[0].decorate(decorators[1].decorate(r))}，
 * decorate 全部发生在提交线程（同步调用），快照语义与单装饰器一致。
 */
public class CompositeTaskDecorator implements TaskDecorator {

    private final List<TaskDecorator> delegates;

    public CompositeTaskDecorator(TaskDecorator... delegates) {
        this.delegates = Arrays.asList(delegates);
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        Runnable result = runnable;
        // 逆序包装 → delegates[0] 在最外层（先快照、最后清理）
        for (int i = delegates.size() - 1; i >= 0; i--) {
            result = delegates.get(i).decorate(result);
        }
        return result;
    }
}
