package com.superprogrammer.chat;

import com.superprogrammer.chat.dto.MemoryLifecycleActionVO;
import com.superprogrammer.chat.dto.MemoryLifecycleProjectVO;
import com.superprogrammer.chat.service.internal.MemoryLifecycleService;
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
 * 计划12 · F-4b 前置 · 生命周期折叠板 IT（@SpringBootTest + PG16）。
 *
 * <p>聚焦 Mockito 测不了的（XML SQL + 数组算子 + join 软删项目 + 多表事务写）：
 * <ul>
 *   <li><b>departed 列表</b>：DEPARTED membership + join 项目名 + 本人 turn 计数（他人 turn 不计）。</li>
 *   <li><b>copy-to 实跑</b>：新项目 + 新栈 OWNER 成员行 + 本人 turns 追加挂 Q（原 P 保留）+ 他人/个人 turns 不动。</li>
 *   <li><b>deleted 列表</b>：deleted_project_ids 引用 + 软删项目名仍取到（XML 绕 @TableLogic）。</li>
 *   <li><b>restore 实跑</b>：移出 deleted_project_ids + 重挂 Q + 本人波及通知置 resolved（他人不动）。</li>
 *   <li><b>权边界</b>：copy-to ACTIVE 403 / restore 无待拉取 404。</li>
 * </ul>
 *
 * <p>跑法：
 * <pre>
 *   mvn -f backend/pom.xml test -Dsurefire.excludedGroups= -Dtest=MemoryLifecycleIT
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("it")
@Tag("integration")
@Transactional
@Rollback
class MemoryLifecycleIT {

    @Autowired MemoryLifecycleService lifecycleService;
    @Autowired JdbcTemplate jdbc;

    private long uniq() {
        return System.nanoTime();
    }

    private Long createUser(String username) {
        return jdbc.queryForObject(
                "INSERT INTO users(username, password) VALUES(?, 'pw') RETURNING id",
                Long.class, username);
    }

    private Long createProject(String name) {
        return jdbc.queryForObject(
                "INSERT INTO projects(name) VALUES(?) RETURNING id",
                Long.class, name);
    }

    private void addMember(Long pid, Long uid, String role, String status) {
        jdbc.update("INSERT INTO memory_project_members(project_id,user_id,role,recall_admin,status,departed_at) " +
                        "VALUES(?,?,?,false,?, CASE WHEN ?='DEPARTED' THEN NOW() - interval '1 day' ELSE NULL END)",
                pid, uid, role, status, status);
    }

    private Long insertTurn(Long userId, String projectIds, String deletedProjectIds) {
        return jdbc.queryForObject(
                "INSERT INTO memory_turns(user_id, direction, project_ids, deleted_project_ids, born_personal, gen_done) " +
                        "VALUES(?, 'INPUT', ?::bigint[], ?::bigint[], true, true) RETURNING id",
                Long.class, userId, projectIds, deletedProjectIds);
    }

    private List<Long> projectIdsOf(Long turnId) {
        return jdbc.queryForObject(
                "SELECT project_ids FROM memory_turns WHERE id = ?",
                (rs, i) -> {
                    java.sql.Array arr = rs.getArray(1);
                    Long[] ids = (Long[]) arr.getArray();
                    return List.of(ids);
                }, turnId);
    }

    private List<Long> deletedProjectIdsOf(Long turnId) {
        return jdbc.queryForObject(
                "SELECT deleted_project_ids FROM memory_turns WHERE id = ?",
                (rs, i) -> {
                    java.sql.Array arr = rs.getArray(1);
                    Long[] ids = (Long[]) arr.getArray();
                    return List.of(ids);
                }, turnId);
    }

    // ---- 1. departed 列表 ----

    @Test
    void departedList_returnsDepartedWithNameAndMyTurnCount() {
        long u = uniq();
        Long me = createUser("it_lc_d_me_" + u);
        Long other = createUser("it_lc_d_ot_" + u);
        Long pidDeparted = createProject("it_lc_d_proj_" + u);
        Long pidActive = createProject("it_lc_d_projA_" + u);
        addMember(pidDeparted, me, "MEMBER", "DEPARTED");
        addMember(pidActive, me, "MEMBER", "ACTIVE");
        insertTurn(me, "{" + pidDeparted + "}", "{}");
        insertTurn(me, "{" + pidDeparted + "}", "{}");
        insertTurn(other, "{" + pidDeparted + "}", "{}");   // 他人 turn 不计

        List<MemoryLifecycleProjectVO> list = lifecycleService.listDepartedProjects(me);

        assertEquals(1, list.size(), "仅 DEPARTED 项目（ACTIVE 不列）");
        MemoryLifecycleProjectVO row = list.get(0);
        assertEquals(pidDeparted, row.getProjectId());
        assertEquals("it_lc_d_proj_" + u, row.getProjectName(), "join projects 取名");
        assertNotNull(row.getDepartedAt(), "带 departed_at 标注时间");
        assertEquals(2, row.getTurnCount(), "只计本人 turns");
    }

    @Test
    void departedList_noDepartedMembership_empty() {
        Long me = createUser("it_lc_d0_me_" + uniq());
        assertTrue(lifecycleService.listDepartedProjects(me).isEmpty());
    }

    // ---- 2. copy-to ----

    @Test
    void copyTo_appendsNewProjectKeepsOriginal_untouchesOthers() {
        long u = uniq();
        Long me = createUser("it_lc_c_me_" + u);
        Long other = createUser("it_lc_c_ot_" + u);
        Long pid = createProject("it_lc_c_proj_" + u);
        addMember(pid, me, "MEMBER", "DEPARTED");
        Long t1 = insertTurn(me, "{" + pid + "}", "{}");
        Long t2 = insertTurn(me, "{" + pid + "}", "{}");
        Long tOther = insertTurn(other, "{" + pid + "}", "{}");
        Long tPersonal = insertTurn(me, "{}", "{}");

        MemoryLifecycleActionVO vo = lifecycleService.copyDepartedProjectTo(me, pid, null);

        assertEquals(2, vo.getAffectedTurns(), "仅本人挂在 P 的 turns");
        Long q = vo.getNewProjectId();
        // copy 非 move：原 P 保留 + 追加 Q
        assertTrue(projectIdsOf(t1).containsAll(List.of(pid, q)));
        assertTrue(projectIdsOf(t2).containsAll(List.of(pid, q)));
        assertEquals(List.of(pid), projectIdsOf(tOther), "他人 turn 不动");
        assertTrue(projectIdsOf(tPersonal).isEmpty(), "个人 turn 不动");
        // 新项目存在 + 新栈 OWNER 成员行
        assertEquals("「it_lc_c_proj_" + u + "」记忆拉取", vo.getNewProjectName(), "默认命名");
        Integer memRows = jdbc.queryForObject(
                "SELECT count(*) FROM memory_project_members WHERE project_id=? AND user_id=? AND role='OWNER' AND status='ACTIVE'",
                Integer.class, q, me);
        assertEquals(1, memRows, "新栈 OWNER 行（召回 ACL 判定源）");
        Integer oldMemRows = jdbc.queryForObject(
                "SELECT count(*) FROM project_members WHERE project_id=? AND user_id=?",
                Integer.class, q, me);
        assertEquals(1, oldMemRows, "复用 ProjectService 落旧成员行");
    }

    @Test
    void copyTo_activeMember_forbidden() {
        long u = uniq();
        Long me = createUser("it_lc_ca_me_" + u);
        Long pid = createProject("it_lc_ca_proj_" + u);
        addMember(pid, me, "MEMBER", "ACTIVE");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> lifecycleService.copyDepartedProjectTo(me, pid, null));
        assertEquals(403, ex.getCode(), "ACTIVE 不可 copy-to（仅 DEPARTED）");
    }

    // ---- 3. deleted 列表 ----

    @Test
    void deletedList_softDeletedProjectNameStillFound() {
        long u = uniq();
        Long me = createUser("it_lc_x_me_" + u);
        Long pid = createProject("it_lc_x_proj_" + u);
        jdbc.update("UPDATE projects SET deleted = 1 WHERE id = ?", pid);   // 软删项目
        insertTurn(me, "{}", "{" + pid + "}");
        insertTurn(me, "{" + pid + "}", "{" + pid + "}");

        List<MemoryLifecycleProjectVO> list = lifecycleService.listDeletedProjects(me);

        assertEquals(1, list.size());
        MemoryLifecycleProjectVO row = list.get(0);
        assertEquals(pid, row.getProjectId());
        assertEquals("it_lc_x_proj_" + u, row.getProjectName(), "软删项目名仍取到（XML 绕 @TableLogic）");
        assertEquals(2, row.getTurnCount());
    }

    // ---- 4. restore ----

    @Test
    void restore_movesOutDeletedReattachesNewProject_resolvesNotice() {
        long u = uniq();
        Long me = createUser("it_lc_r_me_" + u);
        Long other = createUser("it_lc_r_ot_" + u);
        Long pid = createProject("it_lc_r_proj_" + u);
        jdbc.update("UPDATE projects SET deleted = 1 WHERE id = ?", pid);
        Long t1 = insertTurn(me, "{" + pid + "}", "{" + pid + "}");
        Long t2 = insertTurn(me, "{}", "{" + pid + "}");
        jdbc.update("INSERT INTO memory_notifications(user_id, type, ref_id, message) VALUES(?, 'PROJECT_DELETED_AFFECTED', ?, 'm')",
                me, pid);
        jdbc.update("INSERT INTO memory_notifications(user_id, type, ref_id, message) VALUES(?, 'PROJECT_DELETED_AFFECTED', ?, 'm')",
                other, pid);

        MemoryLifecycleActionVO vo = lifecycleService.restoreDeletedProject(me, pid, "我的拉回项目");

        assertEquals(2, vo.getAffectedTurns());
        Long q = vo.getNewProjectId();
        assertTrue(deletedProjectIdsOf(t1).isEmpty(), "移出 deleted_project_ids");
        assertTrue(deletedProjectIdsOf(t2).isEmpty());
        assertTrue(projectIdsOf(t1).containsAll(List.of(pid, q)), "原挂载保留 + 重挂 Q");
        assertEquals(List.of(q), projectIdsOf(t2), "纯 deleted 引用 turn 重挂 Q");
        // 本人通知 resolved，他人不动
        Integer myResolved = jdbc.queryForObject(
                "SELECT count(*) FROM memory_notifications WHERE user_id=? AND ref_id=? AND resolved_at IS NOT NULL",
                Integer.class, me, pid);
        assertEquals(1, myResolved, "本人波及通知置 resolved（badge 消）");
        Integer otherResolved = jdbc.queryForObject(
                "SELECT count(*) FROM memory_notifications WHERE user_id=? AND ref_id=? AND resolved_at IS NOT NULL",
                Integer.class, other, pid);
        assertEquals(0, otherResolved, "他人通知不动");
        assertEquals("我的拉回项目", vo.getNewProjectName(), "指定名透传");
    }

    @Test
    void restore_noPendingTurns_notFound() {
        long u = uniq();
        Long me = createUser("it_lc_r0_me_" + u);
        Long pid = createProject("it_lc_r0_proj_" + u);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> lifecycleService.restoreDeletedProject(me, pid, null));
        assertEquals(404, ex.getCode(), "无待拉取记忆 → 404（防存在性探测）");
    }
}
