package com.superprogrammer.chat;

import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.chat.service.internal.MemoryDepartedResolver;
import com.superprogrammer.chat.service.internal.MemoryDepartedResolver.DepartedInfo;
import com.superprogrammer.chat.service.internal.MemoryTurnPatcher;
import com.superprogrammer.chat.service.internal.RecallDirection;
import com.superprogrammer.chat.service.internal.RecallScope;
import com.superprogrammer.chat.service.internal.RecallTimeWindow;
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
 * 计划12 · I3-4 · 召回 ACL + 离职开关 IT（@SpringBootTest + PG16）。
 *
 * <p>聚焦 Mockito 测不了的取数层实跑（对齐 I3 plan 出口条件，不调 LLM 的部分）：
 * <ul>
 *   <li><b>DepartedResolver 实跑</b>：join users username + DEPARTED 集 + 标注格式。</li>
 *   <li><b>召回 ACL 隔离</b>（向量 14）：A 经 ACL 授权读 owner → 召回含 owner turn；B 未授权 → 读不到。</li>
 *   <li><b>离职开关开</b>：DEPARTED 曾授权 target 的 turn 可召回。</li>
 *   <li><b>离职开关关</b>：即便 ACL 授权了离职 target 也不召回（优先级高于人员多选）。</li>
 * </ul>
 *
 * <p>Pipeline 标注装配（departedAuthorNotes 端到端）+ summary 不受 ACL 影响（恒只读自己）
 * 涉及 LLM 全链，留 Phase4 E2E（同 D-7 先例：@MockBean LlmGateway + 造全量数据）。
 *
 * <p>跑法：
 * <pre>
 *   mvn -f backend/pom.xml test -Dsurefire.excludedGroups= -Dtest=MemoryRecallAclIT
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("it")
@Tag("integration")
@Transactional
@Rollback
class MemoryRecallAclIT {

    @Autowired MemoryTurnPatcher patcher;
    @Autowired MemoryDepartedResolver departedResolver;
    @Autowired MemoryTurnMapper turnMapper;
    @Autowired JdbcTemplate jdbc;

    private long uniq() {
        return System.nanoTime();
    }

    private Long createUser(String username) {
        return jdbc.queryForObject(
                "INSERT INTO users(username, password) VALUES(?, 'pw') RETURNING id", Long.class, username);
    }

    private Long createProject(String name) {
        return jdbc.queryForObject(
                "INSERT INTO projects(name) VALUES(?) RETURNING id", Long.class, name);
    }

    private void addMember(Long pid, Long uid, String role, Boolean recallAdmin, String status) {
        jdbc.update("INSERT INTO memory_project_members(project_id,user_id,role,recall_admin,status,departed_at) " +
                        "VALUES(?,?,?,?,?, CASE WHEN ?='DEPARTED' THEN NOW() - interval '1 day' ELSE NULL END)",
                pid, uid, role, recallAdmin, status, status);
    }

    /** ACL 授权 reader 读 target（created_by=operator 审计）。 */
    private void grantAcl(Long pid, Long reader, Long target, Long operator) {
        jdbc.update("INSERT INTO memory_recall_acl(project_id,reader_user_id,target_user_id,created_by) VALUES(?,?,?,?)",
                pid, reader, target, operator);
    }

    /** 造流水账（挂项目，gen_done=true，无 tag → allCovered false 召回拼原文）。 */
    private void insertProjectTurn(Long uid, Long pid, String content) {
        MemoryTurn t = new MemoryTurn();
        t.setUserId(uid);
        t.setDirection("INPUT");
        t.setProjectIds(List.of(pid));
        t.setBornPersonal(false);
        t.setGenDone(true);
        t.setRawContent(content);
        t.setTagIds(List.of());
        turnMapper.insert(t);
    }

    private RecallScope projectScope(Long pid, boolean includeDeparted) {
        return new RecallScope(false, List.of(pid), RecallDirection.BOTH, RecallTimeWindow.unbounded(), includeDeparted);
    }

    // ---- 1. DepartedResolver 实跑（join username + 标注）----

    @Test
    void departedResolver_joinUsernameAndAnnotate() {
        long u = uniq();
        Long owner = createUser("it_dep_o_" + u);
        Long dep = createUser("it_dep_d_" + u);
        Long pid = createProject("it_dep_proj_" + u);
        addMember(pid, owner, "OWNER", false, "ACTIVE");
        addMember(pid, dep, "MEMBER", false, "DEPARTED");

        DepartedInfo info = departedResolver.resolveDeparted(pid);

        assertTrue(info.departedIds().contains(dep));
        String note = info.annotations().get(dep);
        assertNotNull(note);
        assertTrue(note.startsWith("已离开人员·it_dep_d_" + u + "·"), "标注含 username + date：" + note);
    }

    // ---- 2. 召回 ACL 隔离（向量 14）：A 授权读 owner / B 未授权 ----

    @Test
    void recallAcl_isolation_AReads_BDenied() {
        long u = uniq();
        Long owner = createUser("it_acl_o_" + u);
        Long a = createUser("it_acl_a_" + u);
        Long b = createUser("it_acl_b_" + u);
        Long pid = createProject("it_acl_proj_" + u);
        addMember(pid, owner, "OWNER", false, "ACTIVE");
        addMember(pid, a, "MEMBER", false, "ACTIVE");
        addMember(pid, b, "MEMBER", false, "ACTIVE");
        grantAcl(pid, a, owner, owner);   // 仅授权 A 读 owner
        insertProjectTurn(owner, pid, "owner fact " + u);

        List<MemoryTurn> aResult = patcher.collectUncovered(projectScope(pid, true), a);
        List<MemoryTurn> bResult = patcher.collectUncovered(projectScope(pid, true), b);

        assertTrue(aResult.stream().anyMatch(t -> owner.equals(t.getUserId())), "A 经 ACL 授权 → 召回含 owner turn");
        assertTrue(bResult.stream().noneMatch(t -> owner.equals(t.getUserId())), "B 未授权 → 读不到 owner turn（向量14）");
    }

    // ---- 3. 离职开关开：DEPARTED 曾授权 target 的 turn 可召回 ----

    @Test
    void departedSwitchOn_召回DepartedTurn() {
        long u = uniq();
        Long owner = createUser("it_sw_on_o_" + u);
        Long a = createUser("it_sw_on_a_" + u);
        Long dep = createUser("it_sw_on_d_" + u);
        Long pid = createProject("it_sw_on_proj_" + u);
        addMember(pid, owner, "OWNER", false, "ACTIVE");
        addMember(pid, a, "MEMBER", false, "ACTIVE");
        addMember(pid, dep, "MEMBER", false, "DEPARTED");
        grantAcl(pid, a, dep, owner);  // A 曾被授权读 departed（保交接）
        insertProjectTurn(dep, pid, "departed fact " + u);

        List<MemoryTurn> result = patcher.collectUncovered(projectScope(pid, true), a);

        assertTrue(result.stream().anyMatch(t -> dep.equals(t.getUserId())),
                "开关开 → DEPARTED 曾授权 target 的 turn 可召回（保交接）");
    }

    // ---- 4. 离职开关关：即便 ACL 授权了离职 target 也不召回（优先级高于人员多选）----

    @Test
    void departedSwitchOff_剔DepartedTurn() {
        long u = uniq();
        Long owner = createUser("it_sw_off_o_" + u);
        Long a = createUser("it_sw_off_a_" + u);
        Long dep = createUser("it_sw_off_d_" + u);
        Long pid = createProject("it_sw_off_proj_" + u);
        addMember(pid, owner, "OWNER", false, "ACTIVE");
        addMember(pid, a, "MEMBER", false, "ACTIVE");
        addMember(pid, dep, "MEMBER", false, "DEPARTED");
        grantAcl(pid, a, dep, owner);   // 即便授权了离职 target
        grantAcl(pid, a, owner, owner); // 对照：ACTIVE target 也授权
        insertProjectTurn(dep, pid, "departed fact " + u);
        insertProjectTurn(owner, pid, "owner fact " + u);  // 对照：ACTIVE 仍召回

        List<MemoryTurn> result = patcher.collectUncovered(projectScope(pid, false), a);

        assertTrue(result.stream().noneMatch(t -> dep.equals(t.getUserId())),
                "开关关 → 剔 DEPARTED turn（优先级高于人员多选，即便 ACL 授权）");
        assertTrue(result.stream().anyMatch(t -> owner.equals(t.getUserId())),
                "ACTIVE 作者 turn 不受影响仍召回");
    }
}
