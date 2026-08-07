package com.superprogrammer.chat.service.internal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 计划12 · C · 写入链 IT（@SpringBootTest + PG16）。
 *
 * <p>聚焦 Mockito 测不了的：<b>BaseMapper.insert 把 Long[] 正确序列化成 bigint[]</b>
 * （@TableField typeHandler 在 insert 路径是否生效，V33 LambdaUpdateWrapper 坑的相邻风险），
 * 以及 gen-off raw 写入落库。
 *
 * <p>二期 P1（V67，FR-006）：turns 纯个人域——born_personal/project_ids 矩阵测试随四列下线；
 * gen 开关恒读全局个人兜底（{@code rag.memory.gen.personal.enabled=false}），项目级开关移路由层。
 *
 * <p><b>gen 全程关态</b> → 不调生成 LLM，纯写链验证，快且确定。
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

    private void genOffPersonal() {
        settingService.updateMemoryGenPersonalEnabled(false);
    }

    // ---- 1. gen 关 + 双侧过过滤 → 2 条 raw turn + bigint[] 序列化 ----

    @Test
    void genOff_writesTwoRawTurns_arraysPersisted() {
        Long uid = createUser("it_gen_" + System.nanoTime());
        genOffPersonal();

        int n = service.processTurn(uid, SESSION, INPUT, OUTPUT);

        assertEquals(2, n);
        Integer cnt = jdbc.queryForObject(
                "SELECT count(*) FROM memory_turns WHERE user_id=?", Integer.class, uid);
        assertEquals(2, cnt);

        // gen off → gen_done=false + raw_content 落库
        Boolean genDone = jdbc.queryForObject(
                "SELECT gen_done FROM memory_turns WHERE user_id=? AND direction='INPUT'",
                Boolean.class, uid);
        assertFalse(genDone, "gen 关应 gen_done=false");
        String raw = jdbc.queryForObject(
                "SELECT raw_content FROM memory_turns WHERE user_id=? AND direction='INPUT'",
                String.class, uid);
        assertEquals(INPUT, raw);

        // raw turn 无 tag → tag_ids 空数组 "{}"（bigint[] 序列化，BaseMapper.insert 走 @TableField typeHandler）
        String tagIdsText = jdbc.queryForObject(
                "SELECT tag_ids::text FROM memory_turns WHERE user_id=? AND direction='INPUT'",
                String.class, uid);
        assertEquals("{}", tagIdsText, "raw turn 无 tag");
    }

    // ---- 2. 两侧均被前置过滤跳过 → 0 写入 ----

    @Test
    void bothSkipped_zeroTurns() {
        Long uid = createUser("it_skip_" + System.nanoTime());
        genOffPersonal();

        int n = service.processTurn(uid, SESSION, "嗯", "很高兴为您服务");

        assertEquals(0, n);
        Integer cnt = jdbc.queryForObject(
                "SELECT count(*) FROM memory_turns WHERE user_id=?", Integer.class, uid);
        assertEquals(0, cnt, "两侧均跳过不写 turn 不写 raw");
    }

    // ---- 3. 一侧跳过 → 仅写未跳侧 ----

    @Test
    void outputSkipped_onlyInputWritten() {
        Long uid = createUser("it_oneside_" + System.nanoTime());
        genOffPersonal();

        // OUTPUT 是套话被跳，INPUT 有事实
        int n = service.processTurn(uid, SESSION, INPUT, "很高兴为您服务");

        assertEquals(1, n);
        Integer cnt = jdbc.queryForObject(
                "SELECT count(*) FROM memory_turns WHERE user_id=?", Integer.class, uid);
        assertEquals(1, cnt);
        String dir = jdbc.queryForObject(
                "SELECT direction FROM memory_turns WHERE user_id=?", String.class, uid);
        assertEquals("INPUT", dir);
    }
}
