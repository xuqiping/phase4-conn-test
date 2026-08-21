package com.superprogrammer.projectgroup.service;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成员复活 IT（真 PG，17x#1）：移除→再加入复活软删行不撞 uk_pgm_group_user；
 * 复活重置（used=0/role=MEMBER/quota=新值/开关覆盖清空）+ ADMIN_ADJUST 流水留痕；
 * 并发双加入恰一成功（复活条件 UPDATE 互斥）。
 */
@SpringBootTest
@Tag("integration")
@ActiveProfiles("it")
class ProjectGroupMemberReviveIT {

    private static final long OWNER = 991300001L;
    private static final long MEMBER = 991300002L;

    @Autowired
    private ProjectGroupService groupService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long groupId;

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("INSERT INTO users (id, username, password, name, status) OVERRIDING SYSTEM VALUE "
                + "VALUES (?, 'it_pg_rv_owner', 'x', 'IT组长', 'ACTIVE')", OWNER);
        jdbc.update("INSERT INTO users (id, username, password, name, status) OVERRIDING SYSTEM VALUE "
                + "VALUES (?, 'it_pg_rv_member', 'x', 'IT成员', 'ACTIVE')", MEMBER);
        groupId = groupService.createGroup(OWNER, "IT复活测试组", null);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM project_group_ledger WHERE group_id IN (SELECT id FROM project_groups WHERE owner_user_id = ?)", OWNER);
        jdbc.update("DELETE FROM project_groups WHERE owner_user_id = ?", OWNER);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", OWNER, MEMBER);
    }

    @Test
    void 移除后再加入_复活不409_状态重置() {
        // 旧状态：限额 100、已用 40、MANAGER、禁 VIDEO、可见性覆盖——复活后应全部复位
        groupService.addMember(groupId, OWNER, false, MEMBER, new BigDecimal("100"));
        jdbc.update("UPDATE project_group_members SET used_points = 40, role = 'MANAGER', "
                + "allowed_kinds = '[\"CHAT\"]', member_visibility_overrides = '{\"VIDEO\":\"OWN\"}' "
                + "WHERE group_id = ? AND user_id = ?", groupId, MEMBER);
        groupService.removeMember(groupId, OWNER, false, MEMBER);

        // 再加入（模拟邀请接受/公共池审批共用 insertMemberRow）：新限额 200
        groupService.addMember(groupId, OWNER, false, MEMBER, new BigDecimal("200"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT deleted, used_points, role, quota_limit_points, allowed_kinds, member_visibility_overrides "
                        + "FROM project_group_members WHERE group_id = ? AND user_id = ?", groupId, MEMBER);
        assertThat(((Number) row.get("deleted")).intValue()).isZero();
        assertThat((BigDecimal) row.get("used_points")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.get("role")).isEqualTo("MEMBER");
        assertThat((BigDecimal) row.get("quota_limit_points")).isEqualByComparingTo(new BigDecimal("200"));
        assertThat(row.get("allowed_kinds")).isNull();
        assertThat(row.get("member_visibility_overrides")).isNull();

        // 全组同人仅一行（复活而非新插）
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_group_members WHERE group_id = ? AND user_id = ?",
                Integer.class, groupId, MEMBER);
        assertThat(cnt).isEqualTo(1);

        // ADMIN_ADJUST 复活留痕
        List<String> remarks = jdbc.queryForList(
                "SELECT remark FROM project_group_ledger WHERE group_id = ? AND type = 'ADMIN_ADJUST'",
                String.class, groupId);
        assertThat(remarks).anyMatch(r -> r != null && r.contains("复活"));
    }

    @Test
    void 并发重复加入_恰一成功() throws Exception {
        groupService.addMember(groupId, OWNER, false, MEMBER, null);
        groupService.removeMember(groupId, OWNER, false, MEMBER);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Future<?>[] fs = new Future[threads];
        for (int i = 0; i < threads; i++) {
            fs[i] = pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    groupService.addMember(groupId, OWNER, false, MEMBER, null);
                    ok.incrementAndGet();
                } catch (BusinessException e) {
                    conflict.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Future<?> f : fs) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        Integer alive = jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_group_members WHERE group_id = ? AND user_id = ? AND deleted = 0",
                Integer.class, groupId, MEMBER);
        assertThat(alive).isEqualTo(1);
    }
}
