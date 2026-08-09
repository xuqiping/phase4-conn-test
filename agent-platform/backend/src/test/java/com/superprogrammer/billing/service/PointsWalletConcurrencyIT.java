package com.superprogrammer.billing.service;

import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 安全体系 S1 · SEC-FR-120 并发扣减 IT（真 PG 行锁 + SQL 守卫 + V80 CHECK）。
 * AC：同用户并发 20 扣 10 积分（余额仅 100）→ 恰好 10 笔成功，余额=0 不为负，
 * 流水笔数=成功笔数且 Σdelta + 余额 = 初始值（余额与流水恒一致）。
 */
@SpringBootTest
@Tag("integration")
@ActiveProfiles("it")
class PointsWalletConcurrencyIT {

    private static final long UID = 990000001L;   // 专用测试账号（user_points_balance 无 FK，独立行）

    @Autowired
    private PointsWalletService walletService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("INSERT INTO user_points_balance (user_id, balance_points, updated_at) VALUES (?, 100.00, NOW())", UID);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM points_ledger WHERE user_id = ?", UID);
        jdbc.update("DELETE FROM user_points_balance WHERE user_id = ?", UID);
        jdbc.update("DELETE FROM idempotency_keys WHERE user_id = ?", UID);
    }

    // AC-SEC-FR-120：并发 20 扣、余额只够 10 笔 → 10 成功 10 拒，余额 0 不为负
    @Test
    void concurrentCharge_neverNegative_ledgerConsistent() throws Exception {
        int threads = 20;
        BigDecimal each = new BigDecimal("10.00");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                try {
                    walletService.charge(UID, each, "CHAT", null, "it-concurrency");
                    return "ok";
                } catch (BusinessException e) {
                    return String.valueOf(e.getCode());
                }
            }));
        }
        assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        long ok = 0;
        long insufficient = 0;
        for (Future<String> f : futures) {
            String r = f.get();
            if ("ok".equals(r)) {
                ok++;
            } else if ("40201".equals(r)) {
                insufficient++;
            }
        }
        assertThat(ok).as("恰好 10 笔成功").isEqualTo(10);
        assertThat(insufficient).as("其余 10 笔余额不足拒扣").isEqualTo(10);

        BigDecimal balance = jdbc.queryForObject(
                "SELECT balance_points FROM user_points_balance WHERE user_id = ?", BigDecimal.class, UID);
        assertThat(balance).as("余额=0 不为负").isEqualByComparingTo("0.00");

        Integer ledgerRows = jdbc.queryForObject(
                "SELECT count(*) FROM points_ledger WHERE user_id = ? AND type = 'CONSUME'", Integer.class, UID);
        assertThat(ledgerRows).as("流水笔数=成功笔数").isEqualTo(10);

        BigDecimal sumDelta = jdbc.queryForObject(
                "SELECT COALESCE(SUM(delta_points),0) FROM points_ledger WHERE user_id = ?", BigDecimal.class, UID);
        assertThat(new BigDecimal("100.00").add(sumDelta)).as("初始 100 + Σdelta = 余额（恒一致）")
                .isEqualByComparingTo(balance);
    }

    // AC-SEC-FR-121：同一幂等键并发 10 次 → 只扣一次，全部返回首次结果
    @Test
    void concurrentCharge_sameIdemKey_chargesOnlyOnce() throws Exception {
        int threads = 10;
        String key = "it-idem-1";
        BigDecimal each = new BigDecimal("10.00");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                try {
                    BigDecimal after = walletService.chargeIdempotent(UID, each, "CHAT", null, "it-idem", key);
                    return "ok:" + after;
                } catch (BusinessException e) {
                    return "err:" + e.getCode();
                }
            }));
        }
        assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        long ok = 0;
        String firstResult = null;
        for (Future<String> f : futures) {
            String r = f.get();
            if (r.startsWith("ok:")) {
                ok++;
                if (firstResult == null) {
                    firstResult = r;
                }
                assertThat(r).as("同键全部返回首次相同结果 balanceAfter=90").isEqualTo("ok:90.00");
            }
        }
        assertThat(ok).as("10 次同键提交全部拿到结果（无 5xx/锁死）").isEqualTo(threads);

        Integer ledgerRows = jdbc.queryForObject(
                "SELECT count(*) FROM points_ledger WHERE user_id = ?", Integer.class, UID);
        assertThat(ledgerRows).as("同键并发只扣一次 → 流水仅 1 笔").isEqualTo(1);

        BigDecimal balance = jdbc.queryForObject(
                "SELECT balance_points FROM user_points_balance WHERE user_id = ?", BigDecimal.class, UID);
        assertThat(balance).isEqualByComparingTo("90.00");

        Integer keyRows = jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE idem_key = ?", Integer.class, key);
        assertThat(keyRows).as("幂等键仅 1 行且已回填").isEqualTo(1);
        String resultRef = jdbc.queryForObject(
                "SELECT result_ref FROM idempotency_keys WHERE idem_key = ?", String.class, key);
        assertThat(resultRef).isNotBlank();
    }

    // AC-SEC-FR-121：扣减失败（余额不足）→ 事务回滚不留死键 → 同键充值后可成功重试
    @Test
    void idemKey_failedCharge_rollsBackKey_retrySucceeds() {
        String key = "it-idem-retry";
        // 余额 100 扣 200 → SQL 守卫拒扣 → 整个事务回滚（占位键随之消失）
        assertThatThrownBy(() -> walletService.chargeIdempotent(UID, new BigDecimal("200.00"),
                "CHAT", null, "m", key))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(40201));
        Integer keysAfterFail = jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE idem_key = ?", Integer.class, key);
        assertThat(keysAfterFail).as("失败回滚不留死键").isEqualTo(0);

        // 同键重试（改金额 50）→ 成功扣减
        BigDecimal after = walletService.chargeIdempotent(UID, new BigDecimal("50.00"), "CHAT", null, "m", key);
        assertThat(after).isEqualByComparingTo("50.00");
    }
}
