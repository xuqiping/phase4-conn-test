package com.superprogrammer.chat;

import com.superprogrammer.project.dto.ProjectCreateRequest;
import com.superprogrammer.project.dto.ProjectMemberVO;
import com.superprogrammer.project.dto.ProjectShareRequest;
import com.superprogrammer.project.dto.ProjectVO;
import com.superprogrammer.project.service.ProjectService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计划12 · 生命周期写侧 hook IT（@SpringBootTest + PG16，总体设计 §3.7）。
 * <p>
 * 二期 P1（V67）改写：turns 纯个人域——离职/删项目不再碰 turns（一期 departed/deleted_project_ids
 * 标记、波及通知、F-4b 拉取折叠板随四列下线）；项目删除级联新增 <b>收录规则 + 收录条目软删</b>。
 *
 * <p>聚焦 Mockito 测不了的（多表级联 + ProjectService 真实接线 + V52 回填 SQL）：
 * <ul>
 *   <li><b>create/addMember</b>：新栈成员行同步落（OWNER/MEMBER ACTIVE）；角色变更 upsert 不重复行。</li>
 *   <li><b>removeMember</b>：置 DEPARTED + departed_at；重加入回 ACTIVE 清 departed_at。turns 无动作。</li>
 *   <li><b>delete</b>：项目总结软删（个人不动）+ coverage/成员行/总结 scope/gen 开关清 +
 *       <b>收录规则/条目软删</b>（个人 scope 与他项目条目不动）。</li>
 *   <li><b>V52 回填</b>：旧栈存量成员 → 新栈 ACTIVE 行，ON CONFLICT 幂等。</li>
 * </ul>
 *
 * <p>跑法：
 * <pre>
 *   mvn -f backend/pom.xml test -Dsurefire.excludedGroups= -Dtest=MemoryLifecycleHookIT
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("it")
@Tag("integration")
@Transactional
@Rollback
class MemoryLifecycleHookIT {

    @Autowired ProjectService projectService;
    @Autowired JdbcTemplate jdbc;

    private long uniq() {
        return System.nanoTime();
    }

    private Long createUser(String username) {
        return jdbc.queryForObject(
                "INSERT INTO users(username, password) VALUES(?, 'pw') RETURNING id",
                Long.class, username);
    }

    /** JDBC 直插项目（绕 ProjectService → 不触发 hook，供「存量未同步」场景用）。 */
    private Long createProjectRaw(String name) {
        return jdbc.queryForObject(
                "INSERT INTO projects(name) VALUES(?) RETURNING id",
                Long.class, name);
    }

    /** 二期 P1：turns 纯个人域（V67 已删四列）——插个人 turn 仅为 coverage 提供合法 turn_id。 */
    private Long insertTurn(Long userId) {
        return jdbc.queryForObject(
                "INSERT INTO memory_turns(user_id, direction, gen_done) VALUES(?, 'INPUT', true) RETURNING id",
                Long.class, userId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private String memberStatus(Long pid, Long uid) {
        return jdbc.query("SELECT status FROM memory_project_members WHERE project_id=? AND user_id=?",
                rs -> rs.next() ? rs.getString(1) : null, pid, uid);
    }

    // ---- 1. create / addMember 同步新栈成员行 ----

    @Test
    void createAndAddMember_syncsMemoryMemberRows() {
        long u = uniq();
        Long owner = createUser("it_hk_c_own_" + u);
        Long member = createUser("it_hk_c_mem_" + u);
        ProjectCreateRequest req = new ProjectCreateRequest();
        req.setName("it_hk_c_proj_" + u);

        ProjectVO project = projectService.create(req, owner);

        assertEquals("OWNER", jdbc.queryForObject(
                "SELECT role FROM memory_project_members WHERE project_id=? AND user_id=? AND status='ACTIVE'",
                String.class, project.getId(), owner), "create 落新栈 OWNER ACTIVE 行");

        ProjectShareRequest share = new ProjectShareRequest();
        share.setUserId(member);
        share.setRole("EDITOR");
        projectService.addMember(project.getId(), share, owner, false);
        assertEquals("MEMBER", jdbc.queryForObject(
                "SELECT role FROM memory_project_members WHERE project_id=? AND user_id=? AND status='ACTIVE'",
                String.class, project.getId(), member), "EDITOR→MEMBER 映射");

        // 角色变更 upsert：仍一行
        share.setRole("VIEWER");
        projectService.addMember(project.getId(), share, owner, false);
        assertEquals(1, count("SELECT count(*) FROM memory_project_members WHERE project_id=? AND user_id=?",
                project.getId(), member), "角色变更 upsert 不重复行");
    }

    // ---- 2. removeMember → DEPARTED；重加入回 ACTIVE（二期 P1：turns 无动作） ----

    @Test
    void removeMember_marksDeparted_rejoinReactivates() {
        long u = uniq();
        Long owner = createUser("it_hk_d_own_" + u);
        Long member = createUser("it_hk_d_mem_" + u);
        ProjectCreateRequest req = new ProjectCreateRequest();
        req.setName("it_hk_d_proj_" + u);
        ProjectVO project = projectService.create(req, owner);
        ProjectShareRequest share = new ProjectShareRequest();
        share.setUserId(member);
        share.setRole("VIEWER");
        ProjectMemberVO memberRow = projectService.addMember(project.getId(), share, owner, false);

        projectService.removeMember(project.getId(), memberRow.getId(), owner, false);

        assertEquals("DEPARTED", memberStatus(project.getId(), member), "置 DEPARTED 不删行");
        assertEquals(1, count("SELECT count(*) FROM memory_project_members WHERE project_id=? AND user_id=? AND departed_at IS NOT NULL",
                project.getId(), member), "departed_at 已记");

        // 重加入 → 回 ACTIVE + 清 departed_at（行不重复）
        projectService.addMember(project.getId(), share, owner, false);
        assertEquals("ACTIVE", memberStatus(project.getId(), member), "重加入回 ACTIVE");
        assertEquals(0, count("SELECT count(*) FROM memory_project_members WHERE project_id=? AND user_id=? AND departed_at IS NOT NULL",
                project.getId(), member), "departed_at 清");
        assertEquals(1, count("SELECT count(*) FROM memory_project_members WHERE project_id=? AND user_id=?",
                project.getId(), member));
    }

    // ---- 3. 项目删除全级联（二期 P1：含收录规则/条目软删；turns/通知随 V67 下线） ----

    @Test
    void deleteProject_clearsProjectScopedRows_includingEntriesAndRules() {
        long u = uniq();
        Long owner = createUser("it_hk_x_own_" + u);
        Long member = createUser("it_hk_x_mem_" + u);
        ProjectCreateRequest req = new ProjectCreateRequest();
        req.setName("it_hk_x_proj_" + u);
        ProjectVO project = projectService.create(req, owner);
        ProjectShareRequest share = new ProjectShareRequest();
        share.setUserId(member);
        projectService.addMember(project.getId(), share, owner, false);
        Long pid = project.getId();

        Long tOwner = insertTurn(owner);
        Long tPersonal = insertTurn(member);

        // 项目总结（软删目标）+ 个人总结（不动）
        Long tag = jdbc.queryForObject(
                "INSERT INTO memory_tags(user_id, topic, label) VALUES(?, '工作', 't') RETURNING id",
                Long.class, owner);
        Long projSummary = jdbc.queryForObject(
                "INSERT INTO memory_summaries(user_id, project_id, tag_id, l1_summary) VALUES(?,?,?,'s') RETURNING id",
                Long.class, owner, pid, tag);
        Long personalSummary = jdbc.queryForObject(
                "INSERT INTO memory_summaries(user_id, project_id, tag_id, l1_summary) VALUES(?,NULL,?,'s') RETURNING id",
                Long.class, owner, tag);
        // coverage：项目 scope（清）+ 个人 scope（不动）
        jdbc.update("INSERT INTO memory_summary_coverage(turn_id, tag_id, summary_id, project_id, user_id) VALUES(?,?,?,?,?)",
                tOwner, tag, projSummary, pid, owner);
        jdbc.update("INSERT INTO memory_summary_coverage(turn_id, tag_id, summary_id, project_id, user_id) VALUES(?,?,?,NULL,?)",
                tPersonal, tag, personalSummary, owner);
        // 总结 scope：PROJECT 行（清，防 worker 复活）；PERSONAL 行 V47 trigger 已建（不动）
        jdbc.update("INSERT INTO memory_consolidation_scopes(user_id, scope_kind, project_id) VALUES(?, 'PROJECT', ?)",
                owner, pid);
        // gen 开关（死行清）
        jdbc.update("INSERT INTO memory_project_settings(project_id, gen_enabled) VALUES(?, true)", pid);
        jdbc.update("INSERT INTO memory_project_user_settings(project_id, user_id, gen_enabled) VALUES(?,?, true)",
                pid, member);
        // 二期 P1：收录规则 + 收录条目（项目资产随项目走）；他项目条目对照组（不动）
        jdbc.update("INSERT INTO memory_project_rules(project_id, rule_text) VALUES(?, '收录本系统相关内容')", pid);
        Long otherPid = createProjectRaw("it_hk_x_other_" + u);
        jdbc.update("INSERT INTO memory_project_entries(project_id, author_user_id, l1_summary, status) VALUES(?,?, 's', 'ACTIVE')",
                pid, owner);
        Long otherEntry = jdbc.queryForObject(
                "INSERT INTO memory_project_entries(project_id, author_user_id, l1_summary, status) VALUES(?,?, 's', 'ACTIVE') RETURNING id",
                Long.class, otherPid, owner);

        projectService.delete(pid, owner, false);

        // 项目总结软删 + 个人总结不动
        assertEquals(1, count("SELECT count(*) FROM memory_summaries WHERE id=? AND deleted=1", projSummary));
        assertEquals(0, count("SELECT count(*) FROM memory_summaries WHERE id=? AND deleted=1", personalSummary));
        // coverage：项目 scope 清 + 个人 scope 不动
        assertEquals(0, count("SELECT count(*) FROM memory_summary_coverage WHERE project_id=?", pid));
        assertEquals(1, count("SELECT count(*) FROM memory_summary_coverage WHERE summary_id=?", personalSummary));
        // 成员行 / PROJECT scope / gen 开关清；PERSONAL scope（trigger 建）不动
        assertEquals(0, count("SELECT count(*) FROM memory_project_members WHERE project_id=?", pid));
        assertEquals(0, count("SELECT count(*) FROM memory_consolidation_scopes WHERE project_id=?", pid));
        assertEquals(1, count("SELECT count(*) FROM memory_consolidation_scopes WHERE user_id=? AND scope_kind='PERSONAL'", owner));
        assertEquals(0, count("SELECT count(*) FROM memory_project_settings WHERE project_id=?", pid));
        assertEquals(0, count("SELECT count(*) FROM memory_project_user_settings WHERE project_id=?", pid));
        // 二期 P1：收录规则 + 收录条目软删；他项目条目不动
        assertEquals(1, count("SELECT count(*) FROM memory_project_rules WHERE project_id=? AND deleted=1", pid));
        assertEquals(1, count("SELECT count(*) FROM memory_project_entries WHERE project_id=? AND deleted=1", pid));
        assertEquals(0, count("SELECT count(*) FROM memory_project_entries WHERE id=? AND deleted=1", otherEntry),
                "他项目条目不动");
    }

    // ---- 4. V52 回填 SQL（存量旧栈成员 → 新栈） ----

    @Test
    void v52BackfillSql_syncsLegacyMembers_idempotent() {
        long u = uniq();
        Long owner = createUser("it_hk_b_own_" + u);
        Long member = createUser("it_hk_b_mem_" + u);
        Long pid = createProjectRaw("it_hk_b_proj_" + u);   // 绕 ProjectService = 存量未同步
        jdbc.update("INSERT INTO project_members(project_id, user_id, role) VALUES(?,?, 'OWNER')", pid, owner);
        jdbc.update("INSERT INTO project_members(project_id, user_id, role) VALUES(?,?, 'EDITOR')", pid, member);
        assertEquals(0, count("SELECT count(*) FROM memory_project_members WHERE project_id=?", pid), "前置：新栈无行");

        // 与 V52__backfill_memory_project_members.sql 同语句（IT 内手动重放验语义+幂等）
        String backfill = "INSERT INTO memory_project_members (project_id, user_id, role, recall_admin, status, created_at, updated_at) " +
                "SELECT pm.project_id, pm.user_id, CASE WHEN pm.role = 'OWNER' THEN 'OWNER' ELSE 'MEMBER' END, " +
                "false, 'ACTIVE', NOW(), NOW() FROM project_members pm " +
                "JOIN projects p ON p.id = pm.project_id AND p.deleted = 0 " +
                "WHERE pm.deleted = 0 ON CONFLICT (project_id, user_id) DO NOTHING";
        jdbc.update(backfill);

        assertEquals("OWNER", jdbc.queryForObject(
                "SELECT role FROM memory_project_members WHERE project_id=? AND user_id=?", String.class, pid, owner));
        assertEquals("MEMBER", jdbc.queryForObject(
                "SELECT role FROM memory_project_members WHERE project_id=? AND user_id=?", String.class, pid, member),
                "EDITOR→MEMBER");
        jdbc.update(backfill);
        assertEquals(2, count("SELECT count(*) FROM memory_project_members WHERE project_id=?", pid), "重放幂等不重复");
    }
}
