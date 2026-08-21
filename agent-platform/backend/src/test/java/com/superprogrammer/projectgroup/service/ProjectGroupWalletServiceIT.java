package com.superprogrammer.projectgroup.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupLedgerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 组钱包核心账务 IT（真 PG，计划5 Step2）：并发防透支/并发限额/幂等/回收在途上限/BACKSTOP/对账不变量。
 * 锁序死锁验证：并发混跑 allocate+chargeGroup（个人→组池 vs 组池→成员 两序交叉）不抛死锁异常。
 */
@SpringBootTest
@Tag("integration")
@ActiveProfiles("it")
class ProjectGroupWalletServiceIT {

    private static final long OWNER = 991200001L;
    private static final long MEMBER = 991200002L;

    @Autowired
    private ProjectGroupService groupService;
    @Autowired
    private ProjectGroupWalletService walletService;
    @Autowired
    private ProjectGroupLedgerMapper ledgerMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private Long groupId;
    private static final AtomicInteger LAST_OK = new AtomicInteger();

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("INSERT INTO users (id, username, password, name, status) OVERRIDING SYSTEM VALUE "
                + "VALUES (?, 'it_pg_owner', 'x', 'IT组长', 'ACTIVE')", OWNER);
        jdbc.update("INSERT INTO users (id, username, password, name, status) OVERRIDING SYSTEM VALUE "
                + "VALUES (?, 'it_pg_member', 'x', 'IT成员', 'ACTIVE')", MEMBER);
        jdbc.update("INSERT INTO user_points_balance (user_id, balance_points) VALUES (?, 0) ON CONFLICT (user_id) DO UPDATE SET balance_points = 0", OWNER);
        groupId = groupService.createGroup(OWNER, "IT钱包测试组", null);
        groupService.addMember(groupId, OWNER, false, MEMBER, null);
    }

    @AfterEach
    void clean() {
        // 顺序：组账先导删（无级联）→ 组软删/物理清 → 个人侧测试流水
        jdbc.update("DELETE FROM project_group_ledger WHERE group_id IN (SELECT id FROM project_groups WHERE owner_user_id = ?)", OWNER);
        jdbc.update("DELETE FROM project_groups WHERE owner_user_id = ?", OWNER);
        jdbc.update("DELETE FROM user_points_balance WHERE user_id = ?", OWNER);
        jdbc.update("DELETE FROM points_ledger WHERE user_id = ? AND ref_type = 'GROUP'", OWNER);
        jdbc.update("DELETE FROM media_gen_tasks WHERE project_group_id IS NOT NULL AND model = 'it-pg-wallet'");
        jdbc.update("DELETE FROM payment_order WHERE user_id = ?", OWNER);
        jdbc.update("DELETE FROM idempotency_keys WHERE user_id IN (?, ?)", OWNER, MEMBER);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", OWNER, MEMBER);
    }

    private void fundOwner(String amount) {
        // 个人余额直充（绕过 grant 落 payment_order）：balance + GROUP 无关流水 ADMIN_GRANT
        jdbc.update("UPDATE user_points_balance SET balance_points = balance_points + ? WHERE user_id = ?", new BigDecimal(amount), OWNER);
        jdbc.update("INSERT INTO points_ledger (user_id, type, delta_points, balance_after, ref_type, remark) "
                + "VALUES (?, 'ADMIN_GRANT', ?, ?, 'ADMIN', 'IT注资')", OWNER, new BigDecimal(amount), new BigDecimal(amount));
    }

    private BigDecimal walletBalance() {
        return jdbc.queryForObject("SELECT balance_points FROM project_group_wallets WHERE group_id = ?", BigDecimal.class, groupId);
    }

    private BigDecimal personalBalance() {
        return jdbc.queryForObject("SELECT balance_points FROM user_points_balance WHERE user_id = ?", BigDecimal.class, OWNER);
    }

    private List<ProjectGroupLedgerEntity> groupLedger() {
        return ledgerMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectGroupLedgerEntity>()
                .eq(ProjectGroupLedgerEntity::getGroupId, groupId)
                .orderByAsc(ProjectGroupLedgerEntity::getId));
    }

    /** V133 对账模板①：末行 balance_after == 钱包余额；正向重建 Σdelta（BACKSTOP 不动组池，剔除）。 */
    private void assertLedgerWalletReconcile() {
        List<ProjectGroupLedgerEntity> rows = groupLedger();
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(rows.size() - 1).getBalanceAfter())
                .isEqualByComparingTo(walletBalance());
        BigDecimal sum = rows.stream()
                .filter(r -> !ProjectGroupLedgerEntity.TYPE_BACKSTOP.equals(r.getType()))
                .map(ProjectGroupLedgerEntity::getDeltaPoints)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(walletBalance());
    }

    @Test
    void 划拨回收往返_两侧账本一致() {
        fundOwner("100");
        walletService.allocate(groupId, OWNER, false, new BigDecimal("40"), "IT划拨");
        assertThat(personalBalance()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(groupLedger()).extracting(ProjectGroupLedgerEntity::getType)
                .containsExactly(ProjectGroupLedgerEntity.TYPE_ALLOCATE);

        walletService.reclaim(groupId, OWNER, false, new BigDecimal("15"), "IT回收");
        assertThat(personalBalance()).isEqualByComparingTo(new BigDecimal("75"));
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("25"));

        String pTypes = jdbc.queryForObject(
                "SELECT string_agg(type, ',' ORDER BY id) FROM points_ledger WHERE user_id = ? AND ref_type = 'GROUP'", String.class, OWNER);
        assertThat(pTypes).isEqualTo("GROUP_ALLOCATE,GROUP_RECLAIM");
        assertLedgerWalletReconcile();
    }

    @Test
    void 功能开关_白名单拦截与放行_V139() {
        fundOwner("100");
        walletService.allocate(groupId, OWNER, false, new BigDecimal("50"), null);

        // 全禁 []：结算 chargeGroup 与入口预检 requireAffordableGroup 双卡均拒，组池分文未动
        jdbc.update("UPDATE project_group_members SET allowed_kinds = '[]' WHERE group_id = ? AND user_id = ?",
                groupId, MEMBER);
        assertThatThrownBy(() -> walletService.chargeGroup(groupId, MEMBER, new BigDecimal("5"), "CHAT", "kb1", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("限制");
        assertThatThrownBy(() -> walletService.requireAffordableGroup(groupId, MEMBER, "CHAT"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("限制");
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("50"));

        // 白名单仅 CHAT：CHAT 通、VIDEO 拒
        jdbc.update("UPDATE project_group_members SET allowed_kinds = '[\"CHAT\"]' WHERE group_id = ? AND user_id = ?",
                groupId, MEMBER);
        walletService.chargeGroup(groupId, MEMBER, new BigDecimal("5"), "CHAT", "kb2", null);
        assertThatThrownBy(() -> walletService.chargeGroup(groupId, MEMBER, new BigDecimal("5"), "VIDEO", "kb3", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("限制");
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("45"));

        // 恢复不限：VIDEO 通
        jdbc.update("UPDATE project_group_members SET allowed_kinds = NULL WHERE group_id = ? AND user_id = ?",
                groupId, MEMBER);
        walletService.chargeGroup(groupId, MEMBER, new BigDecimal("5"), "VIDEO", "kb4", null);
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("40"));
    }

    @Test
    void 划拨_个人余额不足_整体回滚() {
        fundOwner("10");
        assertThatThrownBy(() -> walletService.allocate(groupId, OWNER, false, new BigDecimal("40"), "超扣"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("积分余额不足");
        assertThat(walletBalance()).isEqualByComparingTo(BigDecimal.ZERO);   // 组池未被加钱
        assertThat(groupLedger()).isEmpty();                                  // 组流水零笔
        assertThat(personalBalance()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    void 并发扣组池_余额不足方恰一人成功() throws Exception {
        fundOwner("10");
        walletService.allocate(groupId, OWNER, false, new BigDecimal("10"), null);
        runConcurrentCharge("6", 2);
        assertThat(LAST_OK.get()).isEqualTo(1);          // 池10、两笔各6，条件扣减只放行一笔
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("4"));
        long consumeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_group_ledger WHERE group_id = ? AND type = 'CONSUME'", Long.class, groupId);
        assertThat(consumeRows).isEqualTo(1);
        assertLedgerWalletReconcile();
    }

    /** 并发跑 n 线程同额 chargeGroup，成功数落 {@link #LAST_OK}（业务落败合法，由调用方断言值）。 */
    private void runConcurrentCharge(String cost, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                try {
                    walletService.chargeGroup(groupId, MEMBER, new BigDecimal(cost),
                            ProjectGroupLedgerEntity.REF_MEDIA, "it-c" + Thread.currentThread().getId(), null);
                    ok.incrementAndGet();
                } catch (BusinessException expected) {
                    // 余额不足/超限额均合法落败
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        LAST_OK.set(ok.get());
    }

    @Test
    void 并发限额_两笔仅一笔过quota() throws Exception {
        fundOwner("20");
        walletService.allocate(groupId, OWNER, false, new BigDecimal("20"), null);
        groupService.updateQuota(groupId, OWNER, false, MEMBER, new BigDecimal("10"));
        runConcurrentCharge("6", 2);
        assertThat(LAST_OK.get()).isEqualTo(1);          // 6+6=12>10，第二笔被 quota 守卫拒
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("14"));
        BigDecimal used = jdbc.queryForObject(
                "SELECT used_points FROM project_group_members WHERE group_id = ? AND user_id = ?",
                BigDecimal.class, groupId, MEMBER);
        assertThat(used).isEqualByComparingTo(new BigDecimal("6"));
        assertLedgerWalletReconcile();
    }

    @Test
    void 幂等键_重复提交只扣一次() {
        fundOwner("20");
        walletService.allocate(groupId, OWNER, false, new BigDecimal("20"), null);
        String key = "it-media-task-1";
        walletService.chargeGroup(groupId, MEMBER, new BigDecimal("5"), ProjectGroupLedgerEntity.REF_MEDIA, "t1", key);
        BigDecimal replay = walletService.chargeGroup(groupId, MEMBER, new BigDecimal("5"), ProjectGroupLedgerEntity.REF_MEDIA, "t1", key);
        assertThat(replay).isEqualByComparingTo(new BigDecimal("15"));   // 重放返首次扣后余额
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("15"));
        long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_group_ledger WHERE group_id = ? AND type = 'CONSUME'", Long.class, groupId);
        assertThat(rows).isEqualTo(1);

        // 退款同样幂等
        walletService.refundGroup(groupId, MEMBER, new BigDecimal("2"), ProjectGroupLedgerEntity.REF_MEDIA, "t1", key + ":r");
        walletService.refundGroup(groupId, MEMBER, new BigDecimal("2"), ProjectGroupLedgerEntity.REF_MEDIA, "t1", key + ":r");
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("17"));
        BigDecimal used = jdbc.queryForObject(
                "SELECT used_points FROM project_group_members WHERE group_id = ? AND user_id = ?",
                BigDecimal.class, groupId, MEMBER);
        assertThat(used).isEqualByComparingTo(new BigDecimal("3"));      // 5-2
        assertLedgerWalletReconcile();
    }

    @Test
    void 回收在途上限_占用部分不可收() {
        fundOwner("20");
        walletService.allocate(groupId, OWNER, false, new BigDecimal("20"), null);
        jdbc.update("INSERT INTO media_gen_tasks (provider_id, model, task_type, status, request_config, project_group_id, estimated_cost) "
                        + "VALUES (1, 'it-pg-wallet', 'TEXT2VIDEO', 'RUNNING', '{}'::jsonb, ?, 7)", groupId);
        // 余额20、在途7 → 上限13：收 14 拒、收 13 过
        assertThatThrownBy(() -> walletService.reclaim(groupId, OWNER, false, new BigDecimal("14"), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("在途任务占用");
        walletService.reclaim(groupId, OWNER, false, new BigDecimal("13"), null);
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("7"));
        assertLedgerWalletReconcile();
    }

    @Test
    void backstop_差额扣组长组池不动() {
        fundOwner("10");   // 组池留 0
        walletService.backstop(groupId, OWNER, false, new BigDecimal("3"), ProjectGroupLedgerEntity.REF_MEDIA, "t9");
        assertThat(personalBalance()).isEqualByComparingTo(new BigDecimal("7"));   // 组长个人被扣
        assertThat(walletBalance()).isEqualByComparingTo(BigDecimal.ZERO);          // 组池不动
        List<ProjectGroupLedgerEntity> rows = groupLedger();
        assertThat(rows).extracting(ProjectGroupLedgerEntity::getType)
                .containsExactly(ProjectGroupLedgerEntity.TYPE_BACKSTOP);
        assertThat(rows.get(0).getDeltaPoints()).isEqualByComparingTo(new BigDecimal("-3"));
        assertThat(rows.get(0).getBalanceAfter()).isEqualByComparingTo(BigDecimal.ZERO);
        // 成员 used 不受兜底影响（不变量②）
        BigDecimal used = jdbc.queryForObject(
                "SELECT used_points FROM project_group_members WHERE group_id = ? AND user_id = ?",
                BigDecimal.class, groupId, MEMBER);
        assertThat(used).isEqualByComparingTo(BigDecimal.ZERO);
        assertLedgerWalletReconcile();
    }

    @Test
    void 死锁混跑_双向操作与组内操作交叉并发() throws Exception {
        fundOwner("50");
        walletService.allocate(groupId, OWNER, false, new BigDecimal("10"), null);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger dead = new AtomicInteger();
        for (int i = 0; i < 4; i++) {
            final int n = i;
            pool.submit(() -> {
                start.await();
                try {
                    if (n % 2 == 0) {
                        // 双向序：个人→组池（划拨 1 / 回收 1 交替，池够）
                        walletService.allocate(groupId, OWNER, false, BigDecimal.ONE, null);
                        walletService.reclaim(groupId, OWNER, false, BigDecimal.ONE, null);
                    } else {
                        // 组内序：组池→成员
                        walletService.chargeGroup(groupId, MEMBER, new BigDecimal("0.5"),
                                ProjectGroupLedgerEntity.REF_MEDIA, "it-dl-" + n, null);
                        walletService.refundGroup(groupId, MEMBER, new BigDecimal("0.5"),
                                ProjectGroupLedgerEntity.REF_MEDIA, "it-dl-" + n, null);
                    }
                } catch (Exception e) {
                    if (e.toString().contains("deadlock") || e.getCause() != null && e.getCause().toString().contains("deadlock")) {
                        dead.incrementAndGet();
                    }
                    // 其余业务异常（余额不足等）忽略——本用例只盯死锁
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(dead.get()).as("固定锁序下不应出现死锁").isEqualTo(0);
        assertLedgerWalletReconcile();
    }

    @Test
    void 组管理_删组须先清池_重置used留痕() {
        fundOwner("10");
        walletService.allocate(groupId, OWNER, false, new BigDecimal("10"), null);
        assertThatThrownBy(() -> groupService.deleteGroup(groupId, OWNER, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("先回收");
        walletService.reclaim(groupId, OWNER, false, new BigDecimal("10"), null);
        groupService.deleteGroup(groupId, OWNER, false);   // 软删

        assertThatThrownBy(() -> groupService.rename(groupId, OWNER, false, "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");      // 软删后不可再操作

        // 新组验证 resetUsed：ADMIN_ADJUST delta=0 留痕
        Long g2 = groupService.createGroup(OWNER, "IT重置组", null);
        groupService.addMember(g2, OWNER, false, MEMBER, null);
        fundOwner("5");
        walletService.allocate(g2, OWNER, false, new BigDecimal("5"), null);
        walletService.chargeGroup(g2, MEMBER, new BigDecimal("2"), ProjectGroupLedgerEntity.REF_MEDIA, "r1", null);
        groupService.resetUsed(g2, OWNER, false, MEMBER);
        BigDecimal used = jdbc.queryForObject(
                "SELECT used_points FROM project_group_members WHERE group_id = ? AND user_id = ?",
                BigDecimal.class, g2, MEMBER);
        assertThat(used).isEqualByComparingTo(BigDecimal.ZERO);
        String remark = jdbc.queryForObject(
                "SELECT remark FROM project_group_ledger WHERE group_id = ? AND type = 'ADMIN_ADJUST'", String.class, g2);
        assertThat(remark).contains("2.00").contains("→0");
        String type = jdbc.queryForObject(
                "SELECT type FROM project_group_ledger WHERE group_id = ? AND delta_points = 0", String.class, g2);
        assertThat(type).isEqualTo("ADMIN_ADJUST");
    }
}
