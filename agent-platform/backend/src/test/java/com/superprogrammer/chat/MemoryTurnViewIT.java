package com.superprogrammer.chat;

import com.superprogrammer.chat.dto.MemoryTurnVO;
import com.superprogrammer.chat.service.internal.MemoryTurnViewService;
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
 * 计划12 · F · 流水账列表读取 IT（@SpringBootTest + PG16）。
 *
 * <p>对齐 plan F 出口条件（流水账页签 + tag 回填）：
 * <ul>
 *   <li><b>仅本人</b>：只返 user_id=self，他人不返（向量 7）。</li>
 *   <li><b>tag label 回填</b>：tagIds 对应 label 正确。</li>
 *   <li><b>软删过滤</b>：deleted=1 不返。</li>
 *   <li><b>倒序</b>：新在前。</li>
 * </ul>
 *
 * <p>二期 P1（V67）：turns 纯个人域——挂载项目名回填随 project_ids 列下线（条目「收录项目」列
 * 由 memory_project_entries 查询承载）。
 *
 * <p>跑法：
 * <pre>
 *   mvn -f backend/pom.xml test -Dsurefire.excludedGroups= -Dtest=MemoryTurnViewIT
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("it")
@Tag("integration")
@Transactional
@Rollback
class MemoryTurnViewIT {

    @Autowired MemoryTurnViewService turnViewService;
    @Autowired JdbcTemplate jdbc;

    private long uniq() {
        return System.nanoTime();
    }

    private Long createUser(String username) {
        return jdbc.queryForObject(
                "INSERT INTO users(username, password) VALUES(?, 'pw') RETURNING id",
                Long.class, username);
    }

    private Long createTag(Long userId, String label) {
        return jdbc.queryForObject(
                "INSERT INTO memory_tags(user_id, subject, topic, label) VALUES(?,'我',?,?) RETURNING id",
                Long.class, userId, "topic-" + label, label);
    }

    private void createTurn(Long userId, Long tagId, boolean genDone, String l1) {
        jdbc.update("INSERT INTO memory_turns(user_id, direction, tag_ids, l1_summary, gen_done) " +
                        "VALUES(?, 'INPUT', ?::bigint[], ?, ?)",
                userId,
                tagId == null ? "{}" : ("{" + tagId + "}"),
                l1,
                genDone);
    }

    @Test
    void listOnlyOwnTurns() {
        long u = uniq();
        Long me = createUser("it_turn_me_" + u);
        Long other = createUser("it_turn_other_" + u);
        createTurn(me, null, true, "mine");
        createTurn(other, null, true, "others");

        List<MemoryTurnVO> list = turnViewService.listMyTurns(me);
        assertEquals(1, list.size(), "只返本人");
        assertEquals("mine", list.get(0).getL1Summary());
    }

    @Test
    void tagLabelsEnriched() {
        long u = uniq();
        Long me = createUser("it_turn_enr_" + u);
        Long tag = createTag(me, "住北京");
        createTurn(me, tag, true, "enriched");

        List<MemoryTurnVO> list = turnViewService.listMyTurns(me);
        MemoryTurnVO vo = list.get(0);
        assertEquals(List.of(tag), vo.getTagIds());
        assertEquals(List.of("住北京"), vo.getTagLabels(), "tag label 回填");
    }

    @Test
    void filtersSoftDeleted() {
        long u = uniq();
        Long me = createUser("it_turndel_me_" + u);
        createTurn(me, null, true, "keep");
        jdbc.update("INSERT INTO memory_turns(user_id, direction, l1_summary, gen_done, deleted) " +
                "VALUES(?, 'INPUT', 'del', true, 1)", me);

        List<MemoryTurnVO> list = turnViewService.listMyTurns(me);
        assertEquals(1, list.size());
        assertEquals("keep", list.get(0).getL1Summary());
    }

    @Test
    void genDoneFlagRawContentPassedThrough() {
        long u = uniq();
        Long me = createUser("it_turnraw_me_" + u);
        // raw turn（gen_done=false）
        jdbc.update("INSERT INTO memory_turns(user_id, direction, raw_content, gen_done) " +
                "VALUES(?, 'OUTPUT', 'raw-text', false)", me);
        // generated turn
        createTurn(me, null, true, "generated");

        List<MemoryTurnVO> list = turnViewService.listMyTurns(me);
        assertEquals(2, list.size());
        MemoryTurnVO raw = list.stream().filter(t -> Boolean.FALSE.equals(t.getGenDone())).findFirst().orElseThrow();
        assertEquals("raw-text", raw.getRawContent());
        MemoryTurnVO gen = list.stream().filter(t -> Boolean.TRUE.equals(t.getGenDone())).findFirst().orElseThrow();
        assertEquals("generated", gen.getL1Summary());
    }

    @Test
    void emptyWhenNone() {
        long u = uniq();
        Long me = createUser("it_turnempty_me_" + u);
        assertTrue(turnViewService.listMyTurns(me).isEmpty());
    }
}
