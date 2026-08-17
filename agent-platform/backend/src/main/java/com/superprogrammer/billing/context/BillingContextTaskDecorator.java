package com.superprogrammer.billing.context;

import org.springframework.core.task.TaskDecorator;

/**
 * 线程池任务装饰器：把<b>提交线程</b>的 {@link BillingContext} 透传给<b>子线程</b>，跑完清除。
 *
 * <p>接到 4 个 ThreadPoolTaskExecutor（knowledge/media/memory/reconciliation）：
 * 请求线程提交异步任务时，其 userId 随任务进入池线程 → 池线程内调 gateway 仍能自动归户计费。
 *
 * <p>ThreadLocal 不跨线程继承，故无此装饰器时池线程 current()=null（仅采不扣）——这是 RAG 索引、
 * 记忆生成等异步路径漏扣的根因。本装饰器集中修复。
 *
 * <p>finally 清除——池线程复用，防用户身份跨任务串号。
 */
public class BillingContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 提交线程（请求线程）捕获 userId + 组池 gid 快照（计划5 Step4：gid 随 userId 同路透传）
        Long userId = BillingContext.current();
        Long projectGroupId = BillingContext.currentGroupId();
        return () -> {
            BillingContext.set(userId, projectGroupId);
            try {
                runnable.run();
            } finally {
                BillingContext.clear();
            }
        };
    }
}
