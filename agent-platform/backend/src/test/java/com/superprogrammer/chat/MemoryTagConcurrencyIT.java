package com.superprogrammer.chat;

import com.superprogrammer.chat.service.internal.MemoryTagResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计划12 B · 标签归一并发兜底 IT（坑：标签归一并发同义建两条 → 优先复用 + UNIQUE 兜底）。
 *
 * <p>10 线程并发 resolve 同一 (user,subject,topic) 不同 label（同义）→ 必须只造一条 memory_tags 行。
 * 靠 {@code UNIQUE(user_id,subject,topic) NULLS NOT DISTINCT} 拦截 + resolver 捕获
 * {@code DuplicateKeyException} 改查复用。
 *
 * <p>约定：{@code @Tag("integration")} → surefire 默认排除；跑法：
 * <pre>
 *   mvn test -Dsurefire.excludedGroups= -Dtest=MemoryTagConcurrencyIT \
 *     -DDB_PASSWORD=... -DJWT_SECRET=...
 * </pre>
 * 需 PG16 + pgvector + agent_platform 库。无 {@code @Transactional}——多线程须各自独立事务
 * 才能撞 UNIQUE；手动清理（@AfterEach 删测试用户 + 标签）。
 *
 * <p>注：anchor 路径③在此环境大概率走降级（embed 无 key → null → 跳③），不影响并发兜底验证
 * （兜底逻辑在路径④ insert）。
 */
@SpringBootTest
@Tag("integration")
class MemoryTagConcurrencyIT {

    @Autowired MemoryTagResolver resolver;
    @Autowired JdbcTemplate jdbc;

    private Long testUserId;
    private String salt;

    @AfterEach
    void cleanup() {
        if (testUserId != null) {
            Long uid = testUserId;
            jdbc.update("DELETE FROM memory_tags WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM memory_consolidation_scopes WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM users WHERE id = ?", uid);
        }
    }

    @Test
    void tenThreadsSynonym_onlyOneTagRow() throws Exception {
        // 建测试用户（trigger 自动插 PERSONAL scope，cleanup 一并清）
        salt = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        testUserId = jdbc.queryForObject(
                "INSERT INTO users(username, password) VALUES(?, 'pw') RETURNING id",
                Long.class, "tagtest_" + salt);

        String subject = "我";
        String topic = "居住-并发-" + salt;   // UNIQUE 槽位
        int n = 10;

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        // 任一线程异常收集
        List<Long> ids = new ArrayList<>();
        AtomicReference<Throwable> firstErr = new AtomicReference<>();

        for (int i = 0; i < n; i++) {
            final String label = "住址同义" + i;   // 同义不同表面形式
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    Long id = resolver.resolve(testUserId, subject, topic, label);
                    synchronized (ids) {
                        ids.add(id);
                    }
                } catch (Throwable t) {
                    firstErr.compareAndSet(null, t);
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();   // 同时起跑，最大化撞 UNIQUE 概率
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "线程池未在限时内结束");
        assertNull(firstErr.get(), () -> "并发 resolve 抛异常: " + firstErr.get());

        // ① 10 次 resolve 全部拿到非 null tag_id
        assertEquals(n, ids.size(), "应有 n 个返回");
        assertTrue(ids.stream().allMatch(java.util.Objects::nonNull), "无 null tag_id");

        // ② DB 只造一条行（UNIQUE 兜底验证）
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM memory_tags WHERE user_id = ? AND topic = ?",
                Integer.class, testUserId, topic);
        assertNotNull(rowCount);
        assertEquals(1, rowCount, "并发同义必须只造一条 memory_tags（UNIQUE 兜底）");

        // ③ 所有 resolve 返回同一个 tag_id（全部复用同一条）
        long distinctIds = ids.stream().distinct().count();
        assertEquals(1, distinctIds, "并发同义词应全部复用同一 tag_id");

        // ④ usage_count 累计 = n-1（语义=「复用次数」：1 个 DB-winner 创建=0，其余 n-1 个复用各 ++）
        Integer usage = jdbc.queryForObject(
                "SELECT usage_count FROM memory_tags WHERE user_id = ? AND topic = ?",
                Integer.class, testUserId, topic);
        assertNotNull(usage);
        assertEquals(n - 1, usage, "1 创建(0) + 9 复用(各++) → usage_count=9");
    }
}
