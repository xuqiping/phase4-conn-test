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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 组钱包核心账务 IT（真 PG，计划5 Step2）：并发防透支/并发限额/幂等/回收在途上限/对账不变量。
 * 锁序死锁验证：并发混跑 allocate+chargeGroup（个人→组池 vs 组池→成员 两序交叉）不抛死锁异常。
 * <p>V161（修复III A6）：旧 public backstop() 已删，兜底用例改走瀑布 chargeGroup(allowDebt=true)；
 * 新增 瀑布四档 / 缺陷1 复演（组池富余不再回滚转组长全额垫）/ 退款按腿反冲 / 欠款冻结闸。
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
    /** B5：充值冲抵欠款验证用。 */
    @Autowired
    private com.superprogrammer.billing.service.PointsWalletService pointsWallet;
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
        // OWNER id 测试专用：全量清个人流水（GROUP 腿 + fundOwner 的 ADMIN 腿 + B5 的 DEBT_REPAY 腿，
        // 后者曾跨 run 残留导致 IncorrectResultSize）
        jdbc.update("DELETE FROM points_ledger WHERE user_id = ?", OWNER);
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

    /** 成员行单列读（used/self/debt_*）。 */
    private BigDecimal memberCol(String col) {
        return jdbc.queryForObject("SELECT " + col + " FROM project_group_members WHERE group_id = ? AND user_id = ?",
                BigDecimal.class, groupId, MEMBER);
    }

    /** 成员行直改（铺底 used/self/debt 场景态）。 */
    private void setMemberCol(String col, String val) {
        jdbc.update("UPDATE project_group_members SET " + col + " = " + val + " WHERE group_id = ? AND user_id = ?",
                groupId, MEMBER);
    }

    /** 结算口径扣（瀑布扣到底，V161）。 */
    private BigDecimal charge(String cost, String refId) {
        return walletService.chargeGroup(groupId, MEMBER, new BigDecimal(cost),
                ProjectGroupLedgerEntity.REF_MEDIA, refId, null, true);
    }

    /** HOLD 预扣口径（欠款冻结+限额硬卡，超即拒）。 */
    private BigDecimal hold(String cost, String refId) {
        return walletService.chargeGroup(groupId, MEMBER, new BigDecimal(cost),
                ProjectGroupLedgerEntity.REF_MEDIA, refId, null, false);
    }

    private List<ProjectGroupLedgerEntity> groupLedger() {
        return ledgerMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectGroupLedgerEntity>()
                .eq(ProjectGroupLedgerEntity::getGroupId, groupId)
                .orderByAsc(ProjectGroupLedgerEntity::getId));
    }

    /** 同 ref 腿过滤（V161 退款按腿反冲断言用）。 */
    private List<ProjectGroupLedgerEntity> legsOf(String refId) {
        return ledgerMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectGroupLedgerEntity>()
                .eq(ProjectGroupLedgerEntity::getGroupId, groupId)
                .eq(ProjectGroupLedgerEntity::getRefId, refId)
                .orderByAsc(ProjectGroupLedgerEntity::getId));
    }

    /** V133 对账模板①：末行 balance_after == 钱包余额；正向重建 Σdelta。
     *  剔除不动组池的腿：BACKSTOP（差额记组长个人）+ MEMBER_、SELF_、DEBT_ 前缀系
     * （配额授予历史、名下余额腿、核销留痕——均非组池资金腿，V161 扩容）。 */
    private void assertLedgerWalletReconcile() {
        List<ProjectGroupLedgerEntity> rows = groupLedger();
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(rows.size() - 1).getBalanceAfter())
                .isEqualByComparingTo(walletBalance());
        BigDecimal sum = rows.stream()
                .filter(r -> !ProjectGroupLedgerEntity.TYPE_BACKSTOP.equals(r.getType()))
                .filter(r -> !r.getType().startsWith("MEMBER_"))
                .filter(r -> !r.getType().startsWith("SELF_"))
                .filter(r -> !r.getType().startsWith("DEBT_"))
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
        assertThatThrownBy(() -> walletService.chargeGroup(groupId, MEMBER, new BigDecimal("5"),
                "CHAT", "kb1", null, false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("限制");
        assertThatThrownBy(() -> walletService.requireAffordableGroup(groupId, MEMBER, "CHAT"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("限制");
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("50"));

        // 白名单仅 CHAT：CHAT 通、VIDEO 拒
        jdbc.update("UPDATE project_group_members SET allowed_kinds = '[\"CHAT\"]' WHERE group_id = ? AND user_id = ?",
                groupId, MEMBER);
        walletService.chargeGroup(groupId, MEMBER, new BigDecimal("5"), "CHAT", "kb2", null, true);
        assertThatThrownBy(() -> walletService.chargeGroup(groupId, MEMBER, new BigDecimal("5"), "VIDEO", "kb3", null, true))
                .isInstanceOf(BusinessException.class).hasMessageContaining("限制");
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("45"));

        // 恢复不限：VIDEO 通
        jdbc.update("UPDATE project_group_members SET allowed_kinds = NULL WHERE group_id = ? AND user_id = ?",
                groupId, MEMBER);
        walletService.chargeGroup(groupId, MEMBER, new BigDecimal("5"), "VIDEO", "kb4", null, true);
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
    void 并发扣组池_池耗尽部分转组长兜底_池不双扣() throws Exception {
        fundOwner("10");
        walletService.allocate(groupId, OWNER, false, new BigDecimal("10"), null);   // 组长个人归 0、池 10
        runConcurrentCharge("6", 2);
        // V161 瀑布：两笔都成功——第一笔池 6；第二笔池 4 + 缺口 2 组长兜底（个人 0 → 转挂账）
        assertThat(LAST_OK.get()).isEqualTo(2);
        assertThat(walletBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        long consumeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_group_ledger WHERE group_id = ? AND type = 'CONSUME'", Long.class, groupId);
        assertThat(consumeRows).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(ABS(delta_points)),0) FROM project_group_ledger WHERE group_id = ? AND type = 'CONSUME'",
                BigDecimal.class, groupId)).isEqualByComparingTo(new BigDecimal("10"));   // 池恰好扣干不透支
        assertThat(jdbc.queryForObject(
                "SELECT debt_points FROM user_points_balance WHERE user_id = ?", BigDecimal.class, OWNER))
                .isEqualByComparingTo(new BigDecimal("2"));                                // 兜底缺口挂组长账
        assertThat(memberCol("used_points")).isEqualByComparingTo(new BigDecimal("12"));
        assertLedgerWalletReconcile();
    }

    /** 并发跑 n 线程同额 chargeGroup（结算口径），成功数落 {@link #LAST_OK}。 */
    private void runConcurrentCharge(String cost, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                try {
                    walletService.chargeGroup(groupId, MEMBER, new BigDecimal(cost),
                            ProjectGroupLedgerEntity.REF_MEDIA, "it-c" + Thread.currentThread().getId(), null, true);
                    ok.incrementAndGet();
                } catch (BusinessException expected) {
                    // 限额/欠款冻结均合法落败
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
    void 并发限额_HOLD口径_两笔仅一笔过quota() throws Exception {
        fundOwner("20");
        walletService.allocate(groupId, OWNER, false, new BigDecimal("20"), null);
        groupService.updateQuota(groupId, OWNER, false, MEMBER, new BigDecimal("10"));
        runConcurrentHold("6", 2);
        // HOLD（allowDebt=false）限额硬卡：6+6=12>10，第二笔被拒（结算口径会转欠款，预扣必须拦）
        assertThat(LAST_OK.get()).isEqualTo(1);
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("14"));
        assertThat(memberCol("used_points")).isEqualByComparingTo(new BigDecimal("6"));
        assertLedgerWalletReconcile();
    }

    /** 并发 HOLD（allowDebt=false）：超限额线程被硬卡拒。 */
    private void runConcurrentHold(String cost, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                try {
                    walletService.chargeGroup(groupId, MEMBER, new BigDecimal(cost),
                            ProjectGroupLedgerEntity.REF_MEDIA, "it-h" + Thread.currentThread().getId(), null, false);
                    ok.incrementAndGet();
                } catch (BusinessException expected) {
                    // 限额/欠款冻结均合法落败
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
    void 幂等键_重复提交只扣一次() {
        fundOwner("20");
        walletService.allocate(groupId, OWNER, false, new BigDecimal("20"), null);
        String key = "it-media-task-1";
        walletService.chargeGroup(groupId, MEMBER, new BigDecimal("5"), ProjectGroupLedgerEntity.REF_MEDIA, "t1", key, true);
        BigDecimal replay = walletService.chargeGroup(groupId, MEMBER, new BigDecimal("5"), ProjectGroupLedgerEntity.REF_MEDIA, "t1", key, true);
        assertThat(replay).isEqualByComparingTo(new BigDecimal("15"));   // 重放返首次扣后余额
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("15"));
        long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_group_ledger WHERE group_id = ? AND type = 'CONSUME'", Long.class, groupId);
        assertThat(rows).isEqualTo(1);

        // 退款同样幂等
        walletService.refundGroup(groupId, MEMBER, new BigDecimal("2"), ProjectGroupLedgerEntity.REF_MEDIA, "t1", key + ":r");
        walletService.refundGroup(groupId, MEMBER, new BigDecimal("2"), ProjectGroupLedgerEntity.REF_MEDIA, "t1", key + ":r");
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("17"));
        assertThat(memberCol("used_points")).isEqualByComparingTo(new BigDecimal("3"));      // 5-2
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
    void 瀑布四档_池足_池半名下垫_池一名下垫_全组长垫_V161() {
        fundOwner("100");
        // 档1 池足：quota 不设（无限额），池 20 扣 5 → 只落 CONSUME
        walletService.allocate(groupId, OWNER, false, new BigDecimal("20"), null);
        charge("5", "wf1");
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(memberCol("used_points")).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(legsOf("wf1")).extracting(ProjectGroupLedgerEntity::getType)
                .containsExactly(ProjectGroupLedgerEntity.TYPE_CONSUME);
        assertThat(legsOf("wf1").get(0).getDeltaPoints()).isEqualByComparingTo(new BigDecimal("-5"));

        // 档2 池半+名下垫：池收干剩 3、名下 10，扣 5 → 池 3 + 名下 2，无欠款
        walletService.reclaim(groupId, OWNER, false, new BigDecimal("15"), null);          // 池 15→0（组长个人 95）
        walletService.allocate(groupId, OWNER, false, new BigDecimal("3"), null);          // 池 3（组长个人 92）
        setMemberCol("self_points", "10");
        charge("5", "wf2");
        assertThat(walletBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(memberCol("self_points")).isEqualByComparingTo(new BigDecimal("8"));
        assertThat(memberCol("debt_pool_points")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(legsOf("wf2")).extracting(ProjectGroupLedgerEntity::getType)
                .containsExactly(ProjectGroupLedgerEntity.TYPE_CONSUME, ProjectGroupLedgerEntity.TYPE_SELF_CONSUME);
        assertThat(legsOf("wf2").get(0).getDeltaPoints()).isEqualByComparingTo(new BigDecimal("-3"));
        assertThat(legsOf("wf2").get(1).getDeltaPoints()).isEqualByComparingTo(new BigDecimal("-2"));

        // 档3 池 0 名下垫：扣 4 → 全名下
        charge("4", "wf3");
        assertThat(memberCol("self_points")).isEqualByComparingTo(new BigDecimal("4"));
        assertThat(legsOf("wf3")).extracting(ProjectGroupLedgerEntity::getType)
                .containsExactly(ProjectGroupLedgerEntity.TYPE_SELF_CONSUME);

        // 档4 全组长垫：池 0 名下 4，扣 7 → 名下 4 + 组长个人 3
        BigDecimal ownerBefore = personalBalance();
        charge("7", "wf4");
        assertThat(memberCol("self_points")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(personalBalance()).isEqualByComparingTo(ownerBefore.subtract(new BigDecimal("3")));
        assertThat(memberCol("used_points")).isEqualByComparingTo(new BigDecimal("21"));   // 5+5+4+7
        assertThat(legsOf("wf4")).extracting(ProjectGroupLedgerEntity::getType)
                .containsExactly(ProjectGroupLedgerEntity.TYPE_BACKSTOP, ProjectGroupLedgerEntity.TYPE_SELF_CONSUME);
        assertThat(legsOf("wf4").get(0).getRemark()).contains("组长个人承担");
        // 无限额 → 无欠款（溢出=0）
        assertThat(memberCol("debt_leader_points")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(memberCol("debt_pool_points")).isEqualByComparingTo(BigDecimal.ZERO);
        assertLedgerWalletReconcile();
    }

    @Test
    void 缺陷1复演_组池富余不再回滚转组长全额垫_V161() {
        // 场景还原：成员 quota 4000 已用 3750（剩 250），池 6366.6，结算补差 489.95
        fundOwner("7000");
        walletService.allocate(groupId, OWNER, false, new BigDecimal("6366.6"), null);
        groupService.updateQuota(groupId, OWNER, false, MEMBER, new BigDecimal("4000"));
        setMemberCol("used_points", "3750");
        BigDecimal ownerBefore = personalBalance();
        charge("489.95", "defect1");
        // 旧缺陷：addUsed 撞 quota 守卫→整单回滚→组长全额垫 489.95、组池分文未动。
        // 新瀑布：池富余→池全额扣 489.95；组长分文不动；溢出 239.95 转成员欠款（组池垫，shortfall=0）
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("5876.65"));
        assertThat(personalBalance()).isEqualByComparingTo(ownerBefore);          // 组长不再白付
        assertThat(memberCol("used_points")).isEqualByComparingTo(new BigDecimal("4239.95"));
        assertThat(memberCol("debt_pool_points")).isEqualByComparingTo(new BigDecimal("239.95"));
        assertThat(memberCol("debt_leader_points")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(legsOf("defect1")).extracting(ProjectGroupLedgerEntity::getType)
                .containsExactly(ProjectGroupLedgerEntity.TYPE_CONSUME);          // 无 BACKSTOP 腿

        // 欠款冻结：HOLD 预扣被拒（同话术），结算口径不受影响
        assertThatThrownBy(() -> hold("1", "defect1-h"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("欠款");

        // 退款按腿反冲：全额退 489.95 → 池回 489.95、欠款清零、used 回落（超帽 239.95 留痕不回减）
        walletService.refundGroup(groupId, MEMBER, new BigDecimal("489.95"),
                ProjectGroupLedgerEntity.REF_MEDIA, "defect1", null);
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("6366.6"));
        assertThat(memberCol("debt_pool_points")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(memberCol("used_points")).isEqualByComparingTo(new BigDecimal("3989.95"));   // 4239.95−250
        assertLedgerWalletReconcile();
    }

    @Test
    void 退款按腿反冲_混合腿全额退_V161() {
        fundOwner("100");
        walletService.allocate(groupId, OWNER, false, new BigDecimal("3"), null);
        setMemberCol("self_points", "4");
        charge("5", "mix1");   // 池 3 + 名下 2
        assertThat(walletBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(memberCol("self_points")).isEqualByComparingTo(new BigDecimal("2"));
        walletService.refundGroup(groupId, MEMBER, new BigDecimal("5"),
                ProjectGroupLedgerEntity.REF_MEDIA, "mix1", null);
        // 池腿回池、名下腿回名下、used 归零
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(memberCol("self_points")).isEqualByComparingTo(new BigDecimal("4"));
        assertThat(memberCol("used_points")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(legsOf("mix1")).extracting(ProjectGroupLedgerEntity::getType)
                .containsExactly(ProjectGroupLedgerEntity.TYPE_CONSUME, ProjectGroupLedgerEntity.TYPE_SELF_CONSUME,
                        ProjectGroupLedgerEntity.TYPE_REFUND, ProjectGroupLedgerEntity.TYPE_SELF_REFUND);
        assertLedgerWalletReconcile();
    }

    @Test
    void 退款按腿反冲_兜底腿退组长个人_V161() {
        fundOwner("10");       // 池 0、名下 0、组长个人 10
        charge("3", "bs1");    // 全组长垫
        assertThat(personalBalance()).isEqualByComparingTo(new BigDecimal("7"));
        assertThat(legsOf("bs1")).extracting(ProjectGroupLedgerEntity::getType)
                .containsExactly(ProjectGroupLedgerEntity.TYPE_BACKSTOP);
        walletService.refundGroup(groupId, MEMBER, new BigDecimal("3"),
                ProjectGroupLedgerEntity.REF_MEDIA, "bs1", null);
        // 兜底腿原路退组长个人钱包；组池不动；used 归零
        assertThat(personalBalance()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(walletBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(memberCol("used_points")).isEqualByComparingTo(BigDecimal.ZERO);
        // 退款留痕：REFUND delta=0 备注「退组长兜底垫付 3」（不动组池）
        List<ProjectGroupLedgerEntity> legs = legsOf("bs1");
        assertThat(legs.get(1).getType()).isEqualTo(ProjectGroupLedgerEntity.TYPE_REFUND);
        assertThat(legs.get(1).getDeltaPoints()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(legs.get(1).getRemark()).contains("退组长兜底垫付").contains("3");
        assertLedgerWalletReconcile();
    }

    @Test
    void 欠款拆分_组长垫与组池垫_还款先组长后退组池_V161() {
        // quota 5、池 2、名下 0、组长个人 10：扣 8 → 池 2 + 组长垫 6，溢出 3 = 组长垫 3（shortfall 6>3 全归组长）
        fundOwner("10");
        walletService.allocate(groupId, OWNER, false, new BigDecimal("2"), null);
        groupService.updateQuota(groupId, OWNER, false, MEMBER, new BigDecimal("5"));
        charge("8", "ov1");
        assertThat(walletBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(personalBalance()).isEqualByComparingTo(new BigDecimal("2"));   // 组长 10−划拨2−垫付6
        assertThat(memberCol("used_points")).isEqualByComparingTo(new BigDecimal("8"));
        assertThat(memberCol("debt_leader_points")).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(memberCol("debt_pool_points")).isEqualByComparingTo(BigDecimal.ZERO);
        // HOLD 冻结：欠款未清预扣拒
        assertThatThrownBy(() -> hold("1", "ov1-h"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("欠款");
        // 结算口径照常（再扣走组长）
        charge("1", "ov2");
        assertThat(personalBalance()).isEqualByComparingTo(new BigDecimal("1"));
        assertLedgerWalletReconcile();
    }

    @Test
    void 组长个人不足_兜底转挂账_欠款拦消费_充值自动冲抵_B5() {
        fundOwner("3");   // 组长个人 3、组池 0
        // 瀑布兜底 5：组长个人实付 3 → 差额 2 挂账（B5/Q10=A），BACKSTOP 组流水照落
        charge("5", "t-debt");
        assertThat(personalBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        BigDecimal debt = jdbc.queryForObject(
                "SELECT debt_points FROM user_points_balance WHERE user_id = ?", BigDecimal.class, OWNER);
        assertThat(debt).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(groupLedger()).extracting(ProjectGroupLedgerEntity::getType)
                .containsExactly(ProjectGroupLedgerEntity.TYPE_BACKSTOP);
        // 组长侧挂账不影响成员 used 计入（7x-2：used=5 全额）
        assertThat(memberCol("used_points")).isEqualByComparingTo(new BigDecimal("5"));

        // 欠款未清 → 拦全部个人消费入口
        assertThatThrownBy(() -> pointsWallet.requireAffordable(OWNER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("欠款");

        // 充值 4 → 先冲抵 2（DEBT_REPAY 腿），到账 2；欠款清零、消费恢复
        pointsWallet.grant(OWNER, new BigDecimal("4"), BigDecimal.ONE, "ADMIN", null);
        assertThat(personalBalance()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(jdbc.queryForObject(
                "SELECT debt_points FROM user_points_balance WHERE user_id = ?", BigDecimal.class, OWNER))
                .isEqualByComparingTo(BigDecimal.ZERO);
        String repayType = jdbc.queryForObject(
                "SELECT type FROM points_ledger WHERE user_id = ? AND type = 'DEBT_REPAY'", String.class, OWNER);
        assertThat(repayType).isEqualTo("DEBT_REPAY");
        assertThat(pointsWallet.requireAffordable(OWNER)).isEqualByComparingTo(new BigDecimal("2"));
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
                        // 组内序：组池→成员（组长腿在池后锁个人，与双向序理论窄窗，PG 检测器+幂等兜底）
                        walletService.chargeGroup(groupId, MEMBER, new BigDecimal("0.5"),
                                ProjectGroupLedgerEntity.REF_MEDIA, "it-dl-" + n, null, true);
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
        walletService.chargeGroup(g2, MEMBER, new BigDecimal("2"), ProjectGroupLedgerEntity.REF_MEDIA, "r1", null, true);
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
