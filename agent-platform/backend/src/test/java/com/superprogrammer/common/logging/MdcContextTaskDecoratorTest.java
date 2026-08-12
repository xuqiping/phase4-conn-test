package com.superprogrammer.common.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步上下文透传单测（LOG-FR-02 / AC：异步方法日志 traceId 与请求一致、池线程复用不串号）。
 */
class MdcContextTaskDecoratorTest {

    @Test
    void snapshotRestoredInPoolThreadAndClearedAfter() throws Exception {
        MDC.put("traceId", "trace-aaa");
        MDC.put("userId", "42");
        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> afterClear = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable decorated = MdcContextTaskDecorator.wrap(() -> {
            seenTrace.set(MDC.get("traceId"));
        });

        Thread t = new Thread(() -> {
            // 池线程跑第一个任务：应看到提交线程的 traceId
            decorated.run();
            // 跑完后 finally 已清理：同线程复用不残留
            afterClear.set(MDC.get("traceId"));
            latch.countDown();
        });
        t.start();
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(seenTrace.get()).isEqualTo("trace-aaa");
        assertThat(afterClear.get()).isNull();
        MDC.clear();
    }

    @Test
    void emptySnapshotClearsPollutedPoolThread() throws Exception {
        // 提交线程无 MDC：池线程即便被污染也应先清再跑（防上一条任务残留串号）
        MDC.clear();
        AtomicReference<String> seen = new AtomicReference<>();
        // wrap 必须在提交线程调用（快照语义），污染发生在池线程
        Runnable decorated = MdcContextTaskDecorator.wrap(() -> seen.set(MDC.get("traceId")));
        Thread t = new Thread(() -> {
            MDC.put("traceId", "stale-zzz");
            decorated.run();
        });
        t.start();
        t.join(3000);
        assertThat(seen.get()).isNull();
    }

    @Test
    void compositeDecoratesInOrder() {
        // CompositeTaskDecorator：两个装饰器都生效，外层先快照
        CompositeTaskDecorator composite = new CompositeTaskDecorator(
                new MdcContextTaskDecorator(),
                runnable -> () -> { /* billing decorator 替身：直接透传 */ runnable.run(); });
        MDC.put("traceId", "trace-bbb");
        AtomicReference<String> seen = new AtomicReference<>();
        composite.decorate(() -> seen.set(MDC.get("traceId"))).run();
        assertThat(seen.get()).isEqualTo("trace-bbb");
        MDC.clear();
    }
}
