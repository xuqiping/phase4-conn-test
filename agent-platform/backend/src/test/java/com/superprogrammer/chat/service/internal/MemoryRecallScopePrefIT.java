package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryRecallScopeRequest;
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
 * 计划12 · D-7 · 召回 scope 偏好持久化 IT（@SpringBootTest + PG16）。
 *
 * <p>聚焦 Mockito 测不了的：
 * <ul>
 *   <li><b>V50 建表</b>（Flyway 跑过 + 列结构 + UNIQUE user_id WHERE deleted=0）。</li>
 *   <li><b>upsert 无重复</b>（selectOne + insert/updateById 跨次保存同 user 只一行）。</li>
 *   <li><b>bigint[] 序列化</b>（project_ids 经 LongArrayTypeHandler 落库 + 回显）。</li>
 *   <li><b>1:1 user_id 隔离</b>（A 的偏好 B 读不到，向量 7 IDOR 天然隔离）。</li>
 * </ul>
 *
 * <p><b>不覆盖</b> preview 全链 LLM 路径（selector + reader 调 LlmGateway）—— 留 Phase 4 E2E
 * （需 @MockBean LlmGateway + 造 tags/turns/summaries 全量数据），本 IT 只验持久化层确定可跑。
 *
 * <p>跑法：
 * <pre>
 *   mvn -f backend/pom.xml test -Dsurefire.excludedGroups= -Dtest=MemoryRecallScopePrefIT \
 *     -DDB_PASSWORD=... -DJWT_SECRET=...
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("it")
@Tag("integration")
@Transactional
@Rollback
class MemoryRecallScopePrefIT {

    @Autowired
    MemoryRecallScopePreferenceService service;
    @Autowired
    JdbcTemplate jdbc;

    private Long createUser(String username) {
        return jdbc.queryForObject(
                "INSERT INTO users(username, password) VALUES(?, 'pw') RETURNING id",
                Long.class, username);
    }

    private MemoryRecallScopeRequest req(Boolean personalOn, List<Long> projectIds, String direction) {
        MemoryRecallScopeRequest r = new MemoryRecallScopeRequest();
        r.setPersonalOn(personalOn);
        r.setProjectIds(projectIds);
        r.setDirection(direction);
        r.setIncludeDeparted(true);
        return r;
    }

    // ---- 1. V50 建表 + 无历史返 null ----

    @Test
    void noHistory_getScope_returnsNull() {
        Long uid = createUser("it_scope_nh_" + System.nanoTime());
        assertNull(service.getScope(uid));
    }

    // ---- 2. upsert roundTrip + bigint[] 序列化 ----

    @Test
    void saveAndget_roundTrip_arraysPersisted() {
        Long uid = createUser("it_scope_rt_" + System.nanoTime());
        service.saveScope(uid, req(false, List.of(7L, 8L, 9L), "INPUT"));

        MemoryRecallScopeRequest got = service.getScope(uid);
        assertNotNull(got);
        assertEquals(false, got.getPersonalOn());
        assertEquals(List.of(7L, 8L, 9L), got.getProjectIds(), "bigint[] 经 LongArrayTypeHandler 回显");
        assertEquals("INPUT", got.getDirection());
        assertEquals(true, got.getIncludeDeparted());

        // 列结构验证（V50 建表正确）
        String dirs = jdbc.queryForObject(
                "SELECT direction FROM memory_recall_scope_prefs WHERE user_id=?", String.class, uid);
        assertEquals("INPUT", dirs);
    }

    // ---- 3. upsert 无重复（同 user 二次保存 = update 不 insert） ----

    @Test
    void saveTwice_upsertNoDuplicate() {
        Long uid = createUser("it_scope_up_" + System.nanoTime());
        service.saveScope(uid, req(true, List.of(1L), "BOTH"));
        service.saveScope(uid, req(false, List.of(2L, 3L), "OUTPUT"));

        Integer cnt = jdbc.queryForObject(
                "SELECT count(*) FROM memory_recall_scope_prefs WHERE user_id=? AND deleted=0",
                Integer.class, uid);
        assertEquals(1, cnt, "upsert 同 user 只一行");

        MemoryRecallScopeRequest got = service.getScope(uid);
        assertEquals(false, got.getPersonalOn(), "二次保存覆盖");
        assertEquals(List.of(2L, 3L), got.getProjectIds());
        assertEquals("OUTPUT", got.getDirection());
    }

    // ---- 4. null 规范化落库（NOT NULL DEFAULT 列） ----

    @Test
    void saveNullRequest_defaultsPersisted() {
        Long uid = createUser("it_scope_def_" + System.nanoTime());
        service.saveScope(uid, null);  // 全默认

        Boolean personalOn = jdbc.queryForObject(
                "SELECT personal_on FROM memory_recall_scope_prefs WHERE user_id=?", Boolean.class, uid);
        String direction = jdbc.queryForObject(
                "SELECT direction FROM memory_recall_scope_prefs WHERE user_id=?", String.class, uid);
        assertEquals(true, personalOn, "null→true 落库");
        assertEquals("BOTH", direction, "null→BOTH 落库");
    }

    // ---- 5. 1:1 user_id 隔离（向量 7 IDOR） ----

    @Test
    void isolation_userA_notVisibleToUserB() {
        Long a = createUser("it_scope_a_" + System.nanoTime());
        Long b = createUser("it_scope_b_" + System.nanoTime());
        service.saveScope(a, req(false, List.of(7L), "INPUT"));

        assertNull(service.getScope(b), "B 无历史 → null（A 的偏好天然隔离）");
        // B 保存自己的，不影响 A
        service.saveScope(b, req(true, List.of(8L), "OUTPUT"));
        assertEquals(List.of(7L), service.getScope(a).getProjectIds(), "A 偏好不受 B 影响");
        assertEquals(List.of(8L), service.getScope(b).getProjectIds());

        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM memory_recall_scope_prefs WHERE user_id IN (?,?) AND deleted=0",
                Integer.class, a, b);
        assertEquals(2, total, "两用户各一行");
    }
}
