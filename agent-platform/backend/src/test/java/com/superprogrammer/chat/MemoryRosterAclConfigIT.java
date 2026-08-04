package com.superprogrammer.chat;

import com.superprogrammer.chat.dto.MemoryRosterVO;
import com.superprogrammer.chat.dto.MemoryRecallAclRequest;
import com.superprogrammer.chat.dto.MemoryRecallAclVO;
import com.superprogrammer.chat.service.internal.MemoryRecallAclConfigService;
import com.superprogrammer.chat.service.internal.MemoryRosterService;
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
 * 计划12 · I2-4 · 花名册 + ACL 配置 IT（@SpringBootTest + PG16）。
 *
 * <p>聚焦 Mockito 测不了的（对齐 I2 plan 出口条件）：
 * <ul>
 *   <li><b>findRoster join users</b>：返项目全部成员（含 DEPARTED + departed_at + username/name）。</li>
 *   <li><b>isConfigurable 权边界实跑</b>：owner / admin+recall_admin / admin 非 recall_admin / member / 非成员 / DEPARTED。</li>
 *   <li><b>replaceAll 全量替换 + 审计</b>：删旧+插新原子 / target ∩ 成员过滤 / created_by 落库 / 二次替换删旧。</li>
 *   <li><b>getMatrix join username</b>：reader/target 显示名回显。</li>
 * </ul>
 *
 * <p>controller 403 边界已在 {@code MemoryRosterControllerTest} 单测覆盖，本 IT 不重复。
 *
 * <p>跑法：
 * <pre>
 *   mvn -f backend/pom.xml test -Dsurefire.excludedGroups= -Dtest=MemoryRosterAclConfigIT
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("it")
@Tag("integration")
@Transactional
@Rollback
class MemoryRosterAclConfigIT {

    @Autowired MemoryRosterService rosterService;
    @Autowired MemoryRecallAclConfigService aclConfigService;
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

    /** 建成员行；DEPARTED 时 departed_at = NOW()-1d（验标注时间）。 */
    private void addMember(Long pid, Long uid, String role, Boolean recallAdmin, String status) {
        jdbc.update("INSERT INTO memory_project_members(project_id,user_id,role,recall_admin,status,departed_at) " +
                        "VALUES(?,?,?,?,?, CASE WHEN ?='DEPARTED' THEN NOW() - interval '1 day' ELSE NULL END)",
                pid, uid, role, recallAdmin, status, status);
    }

    private Integer aclCount(Long pid, Long reader) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM memory_recall_acl WHERE project_id=? AND reader_user_id=?",
                Integer.class, pid, reader);
    }

    // ---- 1. findRoster join users 含 DEPARTED ----

    @Test
    void roster_includesDeparted_withUsernames() {
        long u = uniq();
        Long owner = createUser("it_roster_o_" + u);
        Long member = createUser("it_roster_m_" + u);
        Long departed = createUser("it_roster_d_" + u);
        Long pid = createProject("it_roster_proj_" + u);
        addMember(pid, owner, "OWNER", false, "ACTIVE");
        addMember(pid, member, "MEMBER", false, "ACTIVE");
        addMember(pid, departed, "MEMBER", false, "DEPARTED");

        List<MemoryRosterVO> roster = rosterService.getRoster(pid);

        assertEquals(3, roster.size(), "含 DEPARTED 全返（保交接）");
        MemoryRosterVO dep = roster.stream().filter(r -> departed.equals(r.getUserId())).findFirst().orElseThrow();
        assertEquals("DEPARTED", dep.getStatus());
        assertNotNull(dep.getDepartedAt(), "DEPARTED 行带 departed_at（标注用）");
        assertEquals("it_roster_d_" + u, dep.getUsername(), "join users.username 回显");
        // owner 排序在前
        assertEquals("OWNER", roster.get(0).getRole());
    }

    @Test
    void roster_emptyProject_empty() {
        Long pid = createProject("it_roster_empty_" + uniq());
        assertTrue(rosterService.getRoster(pid).isEmpty());
    }

    // ---- 2. isConfigurable 权边界（向量 14，实跑 DB）----

    @Test
    void isConfigurable_boundary() {
        long u = uniq();
        Long owner = createUser("it_cfg_o_" + u);
        Long adminRa = createUser("it_cfg_ara_" + u);   // admin + recall_admin
        Long adminNoRa = createUser("it_cfg_anra_" + u); // admin 非 recall_admin
        Long member = createUser("it_cfg_m_" + u);
        Long outsider = createUser("it_cfg_x_" + u);
        Long pid = createProject("it_cfg_proj_" + u);
        addMember(pid, owner, "OWNER", false, "ACTIVE");
        addMember(pid, adminRa, "ADMIN", true, "ACTIVE");
        addMember(pid, adminNoRa, "ADMIN", false, "ACTIVE");
        addMember(pid, member, "MEMBER", false, "ACTIVE");

        assertTrue(aclConfigService.isConfigurable(pid, owner), "owner 兜底");
        assertTrue(aclConfigService.isConfigurable(pid, adminRa), "admin+recall_admin");
        assertFalse(aclConfigService.isConfigurable(pid, adminNoRa), "admin 非 recall_admin");
        assertFalse(aclConfigService.isConfigurable(pid, member), "member");
        assertFalse(aclConfigService.isConfigurable(pid, outsider), "非成员");
    }

    @Test
    void isConfigurable_departed_false() {
        long u = uniq();
        Long departed = createUser("it_cfg_dep_" + u);
        Long pid = createProject("it_cfg_depproj_" + u);
        addMember(pid, departed, "ADMIN", true, "DEPARTED");
        assertFalse(aclConfigService.isConfigurable(pid, departed), "DEPARTED 无配权");
    }

    // ---- 3. replaceAll 全量替换 + 审计（向量 15）----

    @Test
    void replaceAll_insertsRowsWithAudit() {
        long u = uniq();
        Long operator = createUser("it_rep_op_" + u);
        Long reader = createUser("it_rep_r_" + u);
        Long t1 = createUser("it_rep_t1_" + u);
        Long t2 = createUser("it_rep_t2_" + u);
        Long pid = createProject("it_rep_proj_" + u);
        addMember(pid, operator, "OWNER", false, "ACTIVE");
        addMember(pid, reader, "MEMBER", false, "ACTIVE");
        addMember(pid, t1, "MEMBER", false, "ACTIVE");
        addMember(pid, t2, "MEMBER", false, "ACTIVE");
        MemoryRecallAclRequest req = new MemoryRecallAclRequest();
        req.setReaderUserId(reader);
        req.setTargetUserIds(List.of(t1, t2));

        int written = aclConfigService.replaceAll(pid, reader, req, operator);

        assertEquals(2, written);
        assertEquals(2, aclCount(pid, reader));
        // created_by 审计落库
        Long createdBy = jdbc.queryForObject(
                "SELECT created_by FROM memory_recall_acl WHERE project_id=? AND reader_user_id=? LIMIT 1",
                Long.class, pid, reader);
        assertEquals(operator, createdBy);
    }

    @Test
    void replaceAll_secondCall_replacesFully() {
        // 二次 PUT 全量替换：旧 target 集被删，新集写入
        long u = uniq();
        Long operator = createUser("it_rep2_op_" + u);
        Long reader = createUser("it_rep2_r_" + u);
        Long t1 = createUser("it_rep2_t1_" + u);
        Long t2 = createUser("it_rep2_t2_" + u);
        Long pid = createProject("it_rep2_proj_" + u);
        addMember(pid, operator, "OWNER", false, "ACTIVE");
        addMember(pid, reader, "MEMBER", false, "ACTIVE");
        addMember(pid, t1, "MEMBER", false, "ACTIVE");
        addMember(pid, t2, "MEMBER", false, "ACTIVE");
        MemoryRecallAclRequest first = new MemoryRecallAclRequest();
        first.setReaderUserId(reader);
        first.setTargetUserIds(List.of(t1, t2));
        aclConfigService.replaceAll(pid, reader, first, operator);
        assertEquals(2, aclCount(pid, reader));

        MemoryRecallAclRequest second = new MemoryRecallAclRequest();
        second.setReaderUserId(reader);
        second.setTargetUserIds(List.of(t1));  // 只留 t1
        aclConfigService.replaceAll(pid, reader, second, operator);

        assertEquals(1, aclCount(pid, reader), "全量替换：旧 t2 删，只留 t1");
        Integer hasT2 = jdbc.queryForObject(
                "SELECT count(*) FROM memory_recall_acl WHERE project_id=? AND reader_user_id=? AND target_user_id=?",
                Integer.class, pid, reader, t2);
        assertEquals(0, hasT2, "t2 已删");
    }

    @Test
    void replaceAll_emptyTargets_clearsRights() {
        long u = uniq();
        Long operator = createUser("it_repclr_op_" + u);
        Long reader = createUser("it_repclr_r_" + u);
        Long t1 = createUser("it_repclr_t1_" + u);
        Long pid = createProject("it_repclr_proj_" + u);
        addMember(pid, operator, "OWNER", false, "ACTIVE");
        addMember(pid, reader, "MEMBER", false, "ACTIVE");
        addMember(pid, t1, "MEMBER", false, "ACTIVE");
        MemoryRecallAclRequest seed = new MemoryRecallAclRequest();
        seed.setReaderUserId(reader);
        seed.setTargetUserIds(List.of(t1));
        aclConfigService.replaceAll(pid, reader, seed, operator);
        assertEquals(1, aclCount(pid, reader));

        MemoryRecallAclRequest clear = new MemoryRecallAclRequest();
        clear.setReaderUserId(reader);
        clear.setTargetUserIds(List.of());  // 清权
        aclConfigService.replaceAll(pid, reader, clear, operator);

        assertEquals(0, aclCount(pid, reader), "空集 = 清权");
    }

    @Test
    void replaceAll_filtersNonMemberTargets() {
        long u = uniq();
        Long operator = createUser("it_repfilter_op_" + u);
        Long reader = createUser("it_repfilter_r_" + u);
        Long t1 = createUser("it_repfilter_t1_" + u);
        Long nonMember = createUser("it_repfilter_x_" + u);  // 非项目成员
        Long pid = createProject("it_repfilter_proj_" + u);
        addMember(pid, operator, "OWNER", false, "ACTIVE");
        addMember(pid, reader, "MEMBER", false, "ACTIVE");
        addMember(pid, t1, "MEMBER", false, "ACTIVE");
        MemoryRecallAclRequest req = new MemoryRecallAclRequest();
        req.setReaderUserId(reader);
        req.setTargetUserIds(List.of(t1, nonMember));  // nonMember 非成员

        int written = aclConfigService.replaceAll(pid, reader, req, operator);

        assertEquals(1, written, "非成员 target 滤掉");
        assertEquals(1, aclCount(pid, reader));
    }

    @Test
    void replaceAll_keepsDepartedTargets() {
        // DEPARTED 成员仍是合法 target（保交接，§3.7）
        long u = uniq();
        Long operator = createUser("it_repdep_op_" + u);
        Long reader = createUser("it_repdep_r_" + u);
        Long departed = createUser("it_repdep_t_" + u);
        Long pid = createProject("it_repdep_proj_" + u);
        addMember(pid, operator, "OWNER", false, "ACTIVE");
        addMember(pid, reader, "MEMBER", false, "ACTIVE");
        addMember(pid, departed, "MEMBER", false, "DEPARTED");
        MemoryRecallAclRequest req = new MemoryRecallAclRequest();
        req.setReaderUserId(reader);
        req.setTargetUserIds(List.of(departed));

        int written = aclConfigService.replaceAll(pid, reader, req, operator);

        assertEquals(1, written, "DEPARTED target 保留");
    }

    // ---- 4. getMatrix join username ----

    @Test
    void getMatrix_joinUsernames() {
        long u = uniq();
        Long operator = createUser("it_mat_op_" + u);
        Long reader = createUser("it_mat_r_" + u);
        Long target = createUser("it_mat_t_" + u);
        Long pid = createProject("it_mat_proj_" + u);
        addMember(pid, operator, "OWNER", false, "ACTIVE");
        addMember(pid, reader, "MEMBER", false, "ACTIVE");
        addMember(pid, target, "MEMBER", false, "ACTIVE");
        MemoryRecallAclRequest req = new MemoryRecallAclRequest();
        req.setReaderUserId(reader);
        req.setTargetUserIds(List.of(target));
        aclConfigService.replaceAll(pid, reader, req, operator);

        List<MemoryRecallAclVO> matrix = aclConfigService.getMatrix(pid);

        assertEquals(1, matrix.size());
        MemoryRecallAclVO row = matrix.get(0);
        assertEquals(reader, row.getReaderUserId());
        assertEquals(target, row.getTargetUserId());
        assertEquals("it_mat_r_" + u, row.getReaderUsername(), "join reader.username");
        assertEquals("it_mat_t_" + u, row.getTargetUsername(), "join target.username");
        assertEquals(operator, row.getCreatedBy(), "审计 created_by 回显");
    }
}
