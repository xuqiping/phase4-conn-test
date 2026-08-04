package com.superprogrammer.chat;

import com.superprogrammer.chat.dto.MemorySummaryVO;
import com.superprogrammer.chat.service.internal.MemorySummaryViewService;
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
 * 计划12 · F · 总结列表读取 IT（@SpringBootTest + PG16）。
 *
 * <p>对齐 plan F 出口条件（总结页签 + provenance + 状态徽标）：
 * <ul>
 *   <li><b>个人 scope</b>：projectId=null 只返 project_id IS NULL 的总结。</li>
 *   <li><b>项目 scope</b>：projectId=X 只返 project_id=X 的总结。</li>
 *   <li><b>只读自己</b>：他人总结不返（防污染，向量 14）。</li>
 *   <li><b>tag 回填</b>：tagLabel/subject/topic 正确。</li>
 *   <li><b>软删过滤</b>：deleted=1 不返。</li>
 * </ul>
 *
 * <p>跑法：
 * <pre>
 *   mvn -f backend/pom.xml test -Dsurefire.excludedGroups= -Dtest=MemorySummaryViewIT
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("it")
@Tag("integration")
@Transactional
@Rollback
class MemorySummaryViewIT {

    @Autowired MemorySummaryViewService summaryViewService;
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

    private Long createTag(Long userId, String subject, String topic, String label) {
        return jdbc.queryForObject(
                "INSERT INTO memory_tags(user_id, subject, topic, label) VALUES(?,?,?,?) RETURNING id",
                Long.class, userId, subject, topic, label);
    }

    private void createSummary(Long userId, Long projectId, Long tagId, String l1, String status) {
        jdbc.update("INSERT INTO memory_summaries(user_id, project_id, tag_id, l1_summary, l2_detail, " +
                        "source_turn_ids, status) VALUES(?,?,?,?,?,'{}',?)",
                userId, projectId, tagId, l1, "detail-" + l1, status);
    }

    @Test
    void listPersonalScope_onlyOwnNullProject() {
        long u = uniq();
        Long me = createUser("it_sum_me_" + u);
        Long other = createUser("it_sum_other_" + u);
        Long pid = createProject("it_sum_proj_" + u);
        Long tag = createTag(me, "我", "居住", "住北京");
        createSummary(me, null, tag, "p1", "CLEAN");    // 个人
        createSummary(me, pid, tag, "p2", "CLEAN");     // 项目（不应返）
        createSummary(other, null, tag, "p3", "CLEAN"); // 他人（不应返）

        List<MemorySummaryVO> list = summaryViewService.listMySummaries(me, null);

        assertEquals(1, list.size(), "只返自己个人 scope");
        assertEquals("p1", list.get(0).getL1Summary());
        assertNull(list.get(0).getProjectId(), "个人 scope project_id=null");
    }

    @Test
    void listProjectScope_onlyOwnThatProject() {
        long u = uniq();
        Long me = createUser("it_sump_me_" + u);
        Long pid = createProject("it_sump_proj_" + u);
        Long otherPid = createProject("it_sump_proj2_" + u);
        Long tag = createTag(me, "我", "工作", "做开发");
        createSummary(me, pid, tag, "work1", "CLEAN");
        createSummary(me, otherPid, tag, "work2", "CLEAN");
        createSummary(me, null, tag, "work3", "CLEAN");

        List<MemorySummaryVO> list = summaryViewService.listMySummaries(me, pid);

        assertEquals(1, list.size());
        assertEquals(pid, list.get(0).getProjectId());
        assertEquals("work1", list.get(0).getL1Summary());
    }

    @Test
    void tagEnrichment_labelSubjectTopic() {
        long u = uniq();
        Long me = createUser("it_sumtag_me_" + u);
        Long tag = createTag(me, "我", "爱好", "爬山");
        createSummary(me, null, tag, "h1", "CLEAN");

        List<MemorySummaryVO> list = summaryViewService.listMySummaries(me, null);
        MemorySummaryVO vo = list.get(0);
        assertEquals("爬山", vo.getTagLabel());
        assertEquals("我", vo.getSubject());
        assertEquals("爱好", vo.getTopic());
    }

    @Test
    void filtersSoftDeleted() {
        long u = uniq();
        Long me = createUser("it_sumdel_me_" + u);
        Long tag = createTag(me, "我", "居住", "住上海");
        createSummary(me, null, tag, "keep", "CLEAN");
        // 软删一条
        jdbc.update("INSERT INTO memory_summaries(user_id, project_id, tag_id, l1_summary, status, deleted) " +
                "VALUES(?,?,?,?,'CLEAN',1)", me, null, tag, "del");

        List<MemorySummaryVO> list = summaryViewService.listMySummaries(me, null);
        assertEquals(1, list.size());
        assertEquals("keep", list.get(0).getL1Summary());
    }

    @Test
    void statusBadgePassedThrough() {
        long u = uniq();
        Long me = createUser("it_sumstat_me_" + u);
        Long tag = createTag(me, "我", "工作", "做PM");
        createSummary(me, null, tag, "s1", "CLEAN");
        createSummary(me, null, tag, "s2", "PENDING_CONFLICT");
        createSummary(me, null, tag, "s3", "STALE");

        List<MemorySummaryVO> list = summaryViewService.listMySummaries(me, null);
        assertEquals(3, list.size());
        List<String> statuses = list.stream().map(MemorySummaryVO::getStatus).toList();
        assertTrue(statuses.contains("CLEAN"));
        assertTrue(statuses.contains("PENDING_CONFLICT"));
        assertTrue(statuses.contains("STALE"));
    }
}
