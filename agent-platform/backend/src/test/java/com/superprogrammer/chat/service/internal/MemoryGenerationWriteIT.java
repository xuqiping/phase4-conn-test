package com.superprogrammer.chat.service.internal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划12 · C · 写入链 IT（@SpringBootTest + PG16）。
 *
 * <p>聚焦 Mockito 测不了的：<b>BaseMapper.insert 把 Long[] 正确序列化成 bigint[]</b>
 * （@TableField typeHandler 在 insert 路径是否生效，V33 LambdaUpdateWrapper 坑的相邻风险），
 * 以及 born_personal 矩阵 / gen-off raw 写入落库。
 *
 * <p><b>gen 全程关态</b>（项目会话插 {@code memory_project_settings(gen_enabled=false)}；
 * 非项目会话设 {@code rag.memory.gen.personal.enabled=false}）→ 不调生成 LLM，纯写链验证，快且确定。
 * 生成（gen-on）路径由 {@code MemoryGeneratorTest}（C-4）+ Phase 4 E2E 覆盖。
 *
 * <p>跑法：
 * <pre>
 *   mvn test -Dsurefire.excludedGroups= -Dtest=MemoryGenerationWriteIT \
 *     -DDB_PASSWORD=... -DJWT_SECRET=...
 * </pre>
 */
@SpringBootTest
@Tag("integration")
@Transactional
@Rollback
class MemoryGenerationWriteIT {

    private static final Long SESSION = 10L;
    private static final String INPUT = "我住萧山区地铁沿线";   // ≥2 字 + 非 filler → 不过滤
    private static final String OUTPUT = "已记录你的住址信息";  // 非套话 → 不过滤

    @Autowired MemoryGenerationService service;
    @Autowired com.superprogrammer.system.service.SystemSettingService settingService;
    @Autowired JdbcTemplate jdbc;

    private Long createUser(String username) {
        return jdbc.queryForObject(
                "INSERT INTO users(username, password) VALUES(?, 'pw') RETURNING id",
                Long.class, username);
    }

    /** 建项目（满足 memory_project_settings.project_id FK）。 */
    private Long createProject(Long ownerId, String name) {
        return jdbc.queryForObject(
                "INSERT INTO projects(name, created_by) VALUES(?, ?) RETURNING id",
                Long.class, name, ownerId);
    }

    /** 项目会话强制 gen 关（owner 维度），避免默认开导致调真 LLM。 */
    private void disableProjectGen(Long projectId) {
        jdbc.update("INSERT INTO memory_project_settings(project_id, gen_enabled) VALUES(?, false) " +
                "ON CONFLICT (project_id) DO UPDATE SET gen_enabled = false", projectId);
    }

    private void genOffPersonal() {
        settingService.updateMemoryGenPersonalEnabled(false);
    }

    private String projectIdsText(Long uid, String direction) {
        return jdbc.queryForObject(
                "SELECT project_ids::text FROM memory_turns WHERE user_id=? AND direction=?",
                String.class, uid, direction);
    }

    // ---- 1. gen 关 + 双侧过过滤 → 2 条 raw turn + bigint[] 序列化 ----

    @Test
    void genOff_writesTwoRawTurns_arraysPersisted() {
        Long uid = createUser("it_gen_" + System.nanoTime());
        Long pid = createProject(uid, "it_proj_" + System.nanoTime());
        disableProjectGen(pid);

        int n = service.processTurn(uid, SESSION, pid, true, List.of(200L, 300L), INPUT, OUTPUT);

        assertEquals(2, n);
        Integer cnt = jdbc.queryForObject(
                "SELECT count(*) FROM memory_turns WHERE user_id=?", Integer.class, uid);
        assertEquals(2, cnt);

        // bigint[] 序列化（BaseMapper.insert 走 @TableField typeHandler）
        assertEquals("{200,300}", projectIdsText(uid, "INPUT"),
                "BaseMapper.insert 应正确把 Long[] 序列化为 bigint[]");
        assertEquals("{200,300}", projectIdsText(uid, "OUTPUT"));

        // born_personal：勾个人+项目 → true
        Boolean born = jdbc.queryForObject(
                "SELECT born_personal FROM memory_turns WHERE user_id=? AND direction='INPUT'",
                Boolean.class, uid);
        assertTrue(born);

        // gen off → gen_done=false + raw_content 落库
        Boolean genDone = jdbc.queryForObject(
                "SELECT gen_done FROM memory_turns WHERE user_id=? AND direction='INPUT'",
                Boolean.class, uid);
        assertFalse(genDone, "gen 关应 gen_done=false");
        String raw = jdbc.queryForObject(
                "SELECT raw_content FROM memory_turns WHERE user_id=? AND direction='INPUT'",
                String.class, uid);
        assertEquals(INPUT, raw);

        // raw turn 无 tag → tag_ids 空数组 "{}"
        String tagIdsText = jdbc.queryForObject(
                "SELECT tag_ids::text FROM memory_turns WHERE user_id=? AND direction='INPUT'",
                String.class, uid);
        assertEquals("{}", tagIdsText, "raw turn 无 tag");
    }

    // ---- 2. born_personal 矩阵 ----

    @Test
    void projectOnly_noPersonal_bornPersonalFalse() {
        Long uid = createUser("it_bp_" + System.nanoTime());
        Long pid = createProject(uid, "it_proj_bp_" + System.nanoTime());
        disableProjectGen(pid);

        service.processTurn(uid, SESSION, pid, false, List.of(200L), INPUT, OUTPUT);

        Boolean born = jdbc.queryForObject(
                "SELECT born_personal FROM memory_turns WHERE user_id=? AND direction='INPUT'",
                Boolean.class, uid);
        assertFalse(born, "仅项目(取消个人) → bornPersonal=false");
    }

    @Test
    void unloaded_flipsToPersonal() {
        Long uid = createUser("it_unload_" + System.nanoTime());
        Long pid = createProject(uid, "it_proj_ul_" + System.nanoTime());
        disableProjectGen(pid);

        service.processTurn(uid, SESSION, pid, false, List.of(), INPUT, OUTPUT);

        Boolean born = jdbc.queryForObject(
                "SELECT born_personal FROM memory_turns WHERE user_id=? AND direction='INPUT'",
                Boolean.class, uid);
        assertTrue(born, "卸空(无项目+取消个人) → 自动转 bornPersonal=true");
    }

    @Test
    void nonProjectSession_forcedPersonal_emptyProjectIds() {
        Long uid = createUser("it_np_" + System.nanoTime());
        genOffPersonal();

        // 非项目会话即便传项目集 → 强制恒个人 + projectIds 清空
        service.processTurn(uid, SESSION, null, false, List.of(200L), INPUT, OUTPUT);

        assertEquals("{}", projectIdsText(uid, "INPUT"),
                "非项目会话 projectIds 强制空");
        Boolean born = jdbc.queryForObject(
                "SELECT born_personal FROM memory_turns WHERE user_id=? AND direction='INPUT'",
                Boolean.class, uid);
        assertTrue(born, "非项目会话恒个人出身");
    }

    // ---- 3. 两侧均被前置过滤跳过 → 0 写入 ----

    @Test
    void bothSkipped_zeroTurns() {
        Long uid = createUser("it_skip_" + System.nanoTime());
        genOffPersonal();

        int n = service.processTurn(uid, SESSION, null, true, List.of(), "嗯", "很高兴为您服务");

        assertEquals(0, n);
        Integer cnt = jdbc.queryForObject(
                "SELECT count(*) FROM memory_turns WHERE user_id=?", Integer.class, uid);
        assertEquals(0, cnt, "两侧均跳过不写 turn 不写 raw");
    }

    // ---- 4. 一侧跳过 → 仅写未跳侧 ----

    @Test
    void outputSkipped_onlyInputWritten() {
        Long uid = createUser("it_oneside_" + System.nanoTime());
        genOffPersonal();

        // OUTPUT 是套话被跳，INPUT 有事实
        int n = service.processTurn(uid, SESSION, null, true, List.of(), INPUT, "很高兴为您服务");

        assertEquals(1, n);
        Integer cnt = jdbc.queryForObject(
                "SELECT count(*) FROM memory_turns WHERE user_id=?", Integer.class, uid);
        assertEquals(1, cnt);
        String dir = jdbc.queryForObject(
                "SELECT direction FROM memory_turns WHERE user_id=?", String.class, uid);
        assertEquals("INPUT", dir);
    }
}
