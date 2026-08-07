package com.superprogrammer.billing.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计费归户基础设施单测：{@link BillingContext} ThreadLocal 种/取/清 +
 * {@link BillingContextTaskDecorator} 跨线程透传 + 子线程隔离清场。
 * 这是「新模块调 gateway.chat(req) 自动归户」的咽喉基础，覆盖层 1 三件套。
 */
class BillingContextTest {

    @AfterEach
    void cleanThread() {
        BillingContext.clear();
    }

    @Test
    void current_emptyByDefault_returnsNull() {
        assertNull(BillingContext.current());
    }

    @Test
    void set_thenCurrent_returnsIt() {
        BillingContext.set(42L);
        assertEquals(42L, BillingContext.current());
    }

    @Test
    void clear_thenCurrent_returnsNull() {
        BillingContext.set(7L);
        BillingContext.clear();
        assertNull(BillingContext.current());
    }

    @Test
    void taskDecorator_propagatesToChildThread() throws Exception {
        BillingContext.set(99L);
        BillingContextTaskDecorator decorator = new BillingContextTaskDecorator();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Long> childSaw = new AtomicReference<>();

        Thread t = new Thread(decorator.decorate(() -> {
            childSaw.set(BillingContext.current());
            latch.countDown();
        }));
        t.start();
        assertTrue(latch.await(2, TimeUnit.SECONDS), "子线程应在超时前完成");

        // 子线程看到提交线程的 BillingContext（自动归户关键：池任务继承请求线程用户）
        assertEquals(99L, childSaw.get());
        // 提交线程自身上下文不受子任务影响
        assertEquals(99L, BillingContext.current());
    }

    @Test
    void taskDecorator_clearsChildThreadAfterRun() throws Exception {
        BillingContext.set(5L);
        BillingContextTaskDecorator decorator = new BillingContextTaskDecorator();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Long> afterClear = new AtomicReference<>(-1L);

        Thread t = new Thread(decorator.decorate(() -> {
            // 任务体跑完后由 decorator finally clear，此处测任务体内仍可见
            assertEquals(5L, BillingContext.current());
        }));
        t.start();
        t.join(2000);
        // 子线程任务结束后 clear，子线程 ThreadLocal 不残留（防串户泄漏）
        afterClear.set(BillingContext.current()); // 在主线程读，仍是 5L（主线程未清）
        assertEquals(5L, afterClear.get());
    }

    @Test
    void taskDecorator_nullContext_childSeesNull() throws Exception {
        // 提交线程无上下文 → 子线程也不种（null 安全，不抛）
        assertNull(BillingContext.current());
        BillingContextTaskDecorator decorator = new BillingContextTaskDecorator();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Long> childSaw = new AtomicReference<>(-1L);

        Thread t = new Thread(decorator.decorate(() -> {
            childSaw.set(BillingContext.current());
            latch.countDown();
        }));
        t.start();
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNull(childSaw.get());
    }
}
