package com.superprogrammer.chat;

import com.superprogrammer.chat.entity.MemoryNotification;
import com.superprogrammer.chat.service.internal.MemoryNotificationService;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计划12 · F · 波及通知读取 + ACK IT（@SpringBootTest + PG16）。
 *
 * <p>对齐 plan F 出口条件（badge 3s 轮询 + 折叠板详情）：
 * <ul>
 *   <li><b>listUnresolved</b>：返当前用户未处理通知（resolved_at IS NULL），created_at DESC。</li>
 *   <li><b>countUnresolved</b>：badge 计数。</li>
 *   <li><b>ack</b>：置 resolved_at；已处理不重复；他人通知 → 403。</li>
 * </ul>
 *
 * <p>跑法：
 * <pre>
 *   mvn -f backend/pom.xml test -Dsurefire.excludedGroups= -Dtest=MemoryNotificationIT
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("it")
@Tag("integration")
@Transactional
@Rollback
class MemoryNotificationIT {

    @Autowired MemoryNotificationService notificationService;
    @Autowired JdbcTemplate jdbc;

    private long uniq() {
        return System.nanoTime();
    }

    private Long createUser(String username) {
        return jdbc.queryForObject(
                "INSERT INTO users(username, password) VALUES(?, 'pw') RETURNING id",
                Long.class, username);
    }

    private Long seedNotification(Long userId, String type, String message, boolean resolved) {
        return jdbc.queryForObject(
                "INSERT INTO memory_notifications(user_id, type, ref_id, message, resolved_at, created_at) " +
                        "VALUES(?, ?, ?, ?, CASE WHEN ? THEN clock_timestamp() ELSE NULL END, clock_timestamp()) RETURNING id",
                Long.class, userId, type, 100L, message, resolved);
    }

    @Test
    void listUnresolved_returnsOnlyOwnUnresolved_desc() throws InterruptedException {
        long u = uniq();
        Long me = createUser("it_notif_me_" + u);
        Long other = createUser("it_notif_other_" + u);
        // 两条未处理 + 一条已处理；另用户的未处理不计
        seedNotification(me, "SUMMARY_AFFECTED_BY_RECALL", "n1", false);
        Thread.sleep(10);
        seedNotification(me, "PROJECT_DELETED_AFFECTED", "n2", false);
        seedNotification(me, "SUMMARY_AFFECTED_BY_RECALL", "n3-resolved", true);
        seedNotification(other, "SUMMARY_AFFECTED_BY_RECALL", "other-n", false);

        List<MemoryNotification> list = notificationService.listUnresolved(me);

        assertEquals(2, list.size(), "只返自己未处理");
        assertEquals("n2", list.get(0).getMessage(), "最新在前 DESC");
        assertEquals("n1", list.get(1).getMessage(), "DESC 顺序");
        assertNull(list.get(0).getResolvedAt());
        assertNull(list.get(1).getResolvedAt());
    }

    @Test
    void countUnresolved_correct() {
        long u = uniq();
        Long me = createUser("it_notif_cnt_" + u);
        seedNotification(me, "SUMMARY_AFFECTED_BY_RECALL", "a", false);
        seedNotification(me, "PROJECT_DELETED_AFFECTED", "b", false);
        seedNotification(me, "SUMMARY_AFFECTED_BY_RECALL", "c-resolved", true);

        assertEquals(2, notificationService.countUnresolved(me));
    }

    @Test
    void ack_marksResolved() {
        long u = uniq();
        Long me = createUser("it_notif_ack_" + u);
        Long id = seedNotification(me, "SUMMARY_AFFECTED_BY_RECALL", "to-ack", false);

        notificationService.ack(me, id);

        Integer unresolved = jdbc.queryForObject(
                "SELECT count(*) FROM memory_notifications WHERE id=? AND resolved_at IS NULL",
                Integer.class, id);
        assertEquals(0, unresolved, "ack 后 resolved_at 已置");
    }

    @Test
    void ack_alreadyResolved_idempotentNoOp() {
        long u = uniq();
        Long me = createUser("it_notif_ack2_" + u);
        Long id = seedNotification(me, "SUMMARY_AFFECTED_BY_RECALL", "already", true);

        // 已处理再 ack 不报错（update where resolved_at IS NULL 命中 0 行）
        assertDoesNotThrow(() -> notificationService.ack(me, id));
    }

    @Test
    void ack_otherUser_forbidden() {
        long u = uniq();
        Long me = createUser("it_notif_ackme_" + u);
        Long intruder = createUser("it_notif_intruder_" + u);
        Long id = seedNotification(me, "SUMMARY_AFFECTED_BY_RECALL", "mine", false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> notificationService.ack(intruder, id));
        assertEquals(403, ex.getCode(), "他人通知 ACK 拒绝");
    }

    @Test
    void ack_notFound() {
        long u = uniq();
        Long me = createUser("it_notif_nf_" + u);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> notificationService.ack(me, 99999999L));
        assertEquals(404, ex.getCode());
    }
}
