package com.superprogrammer.chat;

import com.superprogrammer.chat.dto.MemoryGenMatrixItemVO;
import com.superprogrammer.chat.service.internal.MemoryGenConfigService;
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
 * 计划12 · F · gen 开关矩阵 IT（@SpringBootTest + PG16）。
 *
 * <p>对齐 plan F L1 出口条件（矩阵 UI + 一键关 + 双开关）：
 * <ul>
 *   <li><b>getMatrix</b>：列我所在的 ACTIVE 项目，owner/member 开关 null→默认开，effective=AND；DEPARTED 不列。</li>
 *   <li><b>setOwnerToggle</b>：仅 OWNER 可改，非 owner → 403；upsert 生效。</li>
 *   <li><b>setMemberOverride</b>：本人可改；非成员 → 403；upsert 生效。</li>
 * </ul>
 *
 * <p>跑法：
 * <pre>
 *   mvn -f backend/pom.xml test -Dsurefire.excludedGroups= -Dtest=MemoryGenConfigIT
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("it")
@Tag("integration")
@Transactional
@Rollback
class MemoryGenConfigIT {

    @Autowired MemoryGenConfigService genConfigService;
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
        jdbc.update("INSERT INTO memory_project_members(project_id,user_id,role,recall_admin,status) " +
                        "VALUES(?,?,?,false,?)", pid, uid, role, status);
    }

    private Boolean ownerToggle(Long pid) {
        List<Boolean> rs = jdbc.queryForList(
                "SELECT gen_enabled FROM memory_project_settings WHERE project_id=?",
                Boolean.class, pid);
        return rs.isEmpty() ? null : rs.get(0);
    }

    private Boolean memberToggle(Long pid, Long uid) {
        List<Boolean> rs = jdbc.queryForList(
                "SELECT gen_enabled FROM memory_project_user_settings WHERE project_id=? AND user_id=?",
                Boolean.class, pid, uid);
        return rs.isEmpty() ? null : rs.get(0);
    }

    // ---- 1. getMatrix 默认开 + effective ----

    @Test
    void getMatrix_defaultsAllOn_noRows() {
        long u = uniq();
        Long owner = createUser("it_gen_o_" + u);
        Long pid = createProject("it_gen_proj_" + u);
        addMember(pid, owner, "OWNER", "ACTIVE");

        List<MemoryGenMatrixItemVO> matrix = genConfigService.getMatrix(owner);

        assertEquals(1, matrix.size());
        MemoryGenMatrixItemVO row = matrix.get(0);
        assertEquals(pid, row.getProjectId());
        assertEquals("it_gen_proj_" + u, row.getProjectName());
        assertEquals("OWNER", row.getRole());
        assertTrue(row.getOwnerEnabled(), "无行默认开");
        assertTrue(row.getMemberEnabled(), "无行默认开");
        assertTrue(row.getEffective(), "AND = 开");
    }

    @Test
    void getMatrix_excludesDeparted() {
        long u = uniq();
        Long me = createUser("it_gen_dep_" + u);
        Long pid = createProject("it_gen_depproj_" + u);
        addMember(pid, me, "MEMBER", "DEPARTED");

        List<MemoryGenMatrixItemVO> matrix = genConfigService.getMatrix(me);
        assertTrue(matrix.isEmpty(), "DEPARTED 不列矩阵");
    }

    @Test
    void getMatrix_ownerOffMemberOn_effectiveOff() {
        long u = uniq();
        Long owner = createUser("it_gen_eff_o_" + u);
        Long member = createUser("it_gen_eff_m_" + u);
        Long pid = createProject("it_gen_effproj_" + u);
        addMember(pid, owner, "OWNER", "ACTIVE");
        addMember(pid, member, "MEMBER", "ACTIVE");
        jdbc.update("INSERT INTO memory_project_settings(project_id,gen_enabled) VALUES(?,false)", pid);

        List<MemoryGenMatrixItemVO> ownerMatrix = genConfigService.getMatrix(owner);
        MemoryGenMatrixItemVO row = ownerMatrix.get(0);
        assertFalse(row.getOwnerEnabled(), "owner 关");
        assertTrue(row.getMemberEnabled(), "member 默认开");
        assertFalse(row.getEffective(), "AND = 关");
    }

    // ---- 2. setOwnerToggle 权边界 + upsert ----

    @Test
    void setOwnerToggle_ownerSets_insertsThenUpdates() {
        long u = uniq();
        Long owner = createUser("it_seto_o_" + u);
        Long pid = createProject("it_seto_proj_" + u);
        addMember(pid, owner, "OWNER", "ACTIVE");

        genConfigService.setOwnerToggle(owner, pid, false);
        assertEquals(false, ownerToggle(pid), "插入关");

        genConfigService.setOwnerToggle(owner, pid, true);
        assertEquals(true, ownerToggle(pid), "更新为开");
    }

    @Test
    void setOwnerToggle_nonOwner_forbidden() {
        long u = uniq();
        Long owner = createUser("it_seto2_o_" + u);
        Long member = createUser("it_seto2_m_" + u);
        Long pid = createProject("it_seto2_proj_" + u);
        addMember(pid, owner, "OWNER", "ACTIVE");
        addMember(pid, member, "MEMBER", "ACTIVE");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> genConfigService.setOwnerToggle(member, pid, false));
        assertEquals(403, ex.getCode(), "非 owner 拒");
        assertNull(ownerToggle(pid), "拒后未写入");
    }

    @Test
    void setOwnerToggle_nonMember_forbidden() {
        long u = uniq();
        Long outsider = createUser("it_seto3_x_" + u);
        Long pid = createProject("it_seto3_proj_" + u);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> genConfigService.setOwnerToggle(outsider, pid, false));
        assertEquals(403, ex.getCode(), "非成员拒");
    }

    // ---- 3. setMemberOverride 权边界 + upsert ----

    @Test
    void setMemberOverride_memberSetsOwn_insertsThenUpdates() {
        long u = uniq();
        Long owner = createUser("it_setm_o_" + u);
        Long member = createUser("it_setm_m_" + u);
        Long pid = createProject("it_setm_proj_" + u);
        addMember(pid, owner, "OWNER", "ACTIVE");
        addMember(pid, member, "MEMBER", "ACTIVE");

        genConfigService.setMemberOverride(member, pid, false);
        assertEquals(false, memberToggle(pid, member));

        genConfigService.setMemberOverride(member, pid, true);
        assertEquals(true, memberToggle(pid, member));
    }

    @Test
    void setMemberOverride_nonMember_forbidden() {
        long u = uniq();
        Long outsider = createUser("it_setm2_x_" + u);
        Long pid = createProject("it_setm2_proj_" + u);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> genConfigService.setMemberOverride(outsider, pid, false));
        assertEquals(403, ex.getCode());
    }

    @Test
    void setMemberOverride_isolatedPerUser() {
        long u = uniq();
        Long owner = createUser("it_setm3_o_" + u);
        Long m1 = createUser("it_setm3_m1_" + u);
        Long m2 = createUser("it_setm3_m2_" + u);
        Long pid = createProject("it_setm3_proj_" + u);
        addMember(pid, owner, "OWNER", "ACTIVE");
        addMember(pid, m1, "MEMBER", "ACTIVE");
        addMember(pid, m2, "MEMBER", "ACTIVE");

        genConfigService.setMemberOverride(m1, pid, false);

        assertEquals(false, memberToggle(pid, m1), "m1 关");
        assertNull(memberToggle(pid, m2), "m2 不受影响（无行默认开）");
    }
}
