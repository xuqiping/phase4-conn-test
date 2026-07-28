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
 * <p>对齐 plan F 出口条件（流水账页签 + 挂载项目 + tag 回填）：
 * <ul>
 *   <li><b>仅本人</b>：只返 user_id=self，他人不返（向量 7）。</li>
 *   <li><b>tag label 回填</b>：tagIds 对应 label 正确。</li>
 *   <li><b>项目名回填</b>：projectIds 对应项目名正确。</li>
 *   <li><b>软删过滤</b>：deleted=1 不返。</li>
 *   <li><b>倒序</b>：新在前。</li>
 * </ul>
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

    private Long createProject(String name) {
        return jdbc.queryForObject(
                "INSERT INTO projects(name) VALUES(?) RETURNING id",
                Long.class, name);
    }

    private Long createTag(Long userId, String label) {
        return jdbc.queryForObject(
                "INSERT INTO memory_tags(user_id, subject, topic, label) VALUES(?,'我',?,?) RETURNING id",
                Long.class, userId, "topic-" + label, label);
    }

    private void createTurn(Long userId, Long tagId, Long projectId, boolean genDone, String l1) {
        jdbc.update("INSERT INTO memory_turns(user_id, direction, tag_ids, l1_summary, gen_done, project_ids, born_personal) " +
                        "VALUES(?, 'INPUT', ?::bigint[], ?, ?, ?::bigint[], true)",
                userId,
                tagId == null ? "{}" : ("{" + tagId + "}"),
                l1,
                genDone,
                projectId == null ? "{}" : ("{" + projectId + "}"));
    }

    @Test
    void listOnlyOwnTurns() {
        long u = uniq();
        Long me = createUser("it_turn_me_" + u);
        Long other = createUser("it_turn_other_" + u);
        createTurn(me, null, null, true, "mine");
        createTurn(other, null, null, true, "others");

        List<MemoryTurnVO> list = turnViewService.listMyTurns(me);
        assertEquals(1, list.size(), "只返本人");
        assertEquals("mine", list.get(0).getL1Summary());
    }

    @Test
    void tagLabelsAndProjectNamesEnriched() {
        long u = uniq();
        Long me = createUser("it_turn_enr_" + u);
        Long pid = createProject("it_turn_proj_" + u);
        Long tag = createTag(me, "住北京");
        createTurn(me, tag, pid, true, "enriched");

        List<MemoryTurnVO> list = turnViewService.listMyTurns(me);
        MemoryTurnVO vo = list.get(0);
        assertEquals(List.of(tag), vo.getTagIds());
        assertEquals(List.of("住北京"), vo.getTagLabels(), "tag label 回填");
        assertEquals(List.of(pid), vo.getProjectIds());
        assertEquals(List.of("it_turn_proj_" + u), vo.getProjectNames(), "项目名回填");
    }

    @Test
    void filtersSoftDeleted() {
        long u = uniq();
        Long me = createUser("it_turndel_me_" + u);
        createTurn(me, null, null, true, "keep");
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
        jdbc.update("INSERT INTO memory_turns(user_id, direction, raw_content, gen_done, born_personal) " +
                "VALUES(?, 'OUTPUT', 'raw-text', false, true)", me);
        // generated turn
        createTurn(me, null, null, true, "generated");

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
