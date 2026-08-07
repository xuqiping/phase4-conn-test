package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 记忆二期 P1 · MemoryEntryRecallService 单测（FR-007 ①.5 读权咽喉）。
 * <p>
 * 覆盖：
 * <ol>
 *   <li>scope 空 / reader null → 空（不查库）。</li>
 *   <li>读者 ACTIVE 成员的项目 → 批量查 ACTIVE 条目（一次 IN，禁 N+1）。</li>
 *   <li>读者 DEPARTED / 非成员的项目 → 静默排除（失读权）。</li>
 *   <li>无可读项目 → 不查条目表。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemoryEntryRecallServiceTest {

    @Mock MemoryProjectMemberMapper memberMapper;
    @Mock MemoryProjectEntryMapper entryMapper;

    private MemoryEntryRecallService service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 需要实体表元数据（纯 Mockito 无 Spring 上下文）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MemoryProjectMember.class);
    }

    @BeforeEach
    void setUp() {
        service = new MemoryEntryRecallService(memberMapper, entryMapper);
    }

    private static MemoryProjectMember membership(long projectId, long userId, String status) {
        MemoryProjectMember m = new MemoryProjectMember();
        m.setProjectId(projectId);
        m.setUserId(userId);
        m.setStatus(status);
        return m;
    }

    private static MemoryProjectEntryVO entry(long id, long projectId) {
        return MemoryProjectEntryVO.builder()
                .id(id).projectId(projectId).authorUserId(2L).authorName("张三")
                .l1Summary("蒸馏 L1").status("ACTIVE").build();
    }

    // ===== 1 入参空 → 空，不查库 =====

    @Test
    void emptyScopeOrNullReader_returnsEmpty_noDb() {
        assertTrue(service.collectActiveEntries(null, 1L).isEmpty());
        assertTrue(service.collectActiveEntries(List.of(), 1L).isEmpty());
        assertTrue(service.collectActiveEntries(List.of(10L), null).isEmpty());
        verifyNoInteractions(memberMapper, entryMapper);
    }

    // ===== 2 ACTIVE 成员项目 → 批量查条目 =====

    @Test
    void activeMemberProjects_batchQueryEntries() {
        when(memberMapper.selectList(any())).thenReturn(List.of(
                membership(10L, 1L, "ACTIVE"),
                membership(20L, 1L, "ACTIVE")));
        when(entryMapper.listActiveForRecall(List.of(10L, 20L))).thenReturn(List.of(
                entry(1, 10L), entry(2, 20L)));

        List<MemoryProjectEntryVO> out = service.collectActiveEntries(List.of(10L, 20L, 30L), 1L);

        assertEquals(2, out.size());
        verify(entryMapper, times(1)).listActiveForRecall(List.of(10L, 20L));  // 一次 IN，禁 N+1
    }

    // ===== 3 DEPARTED 项目被成员查询过滤（DB status=ACTIVE 条件）→ 不返 =====

    @Test
    void departedMembership_filteredByQuery_returnsEmpty() {
        // selectList 带 status=ACTIVE 条件：DEPARTED 行不返（mock 模拟 DB 过滤结果）
        when(memberMapper.selectList(any())).thenReturn(List.of());

        List<MemoryProjectEntryVO> out = service.collectActiveEntries(List.of(10L), 1L);

        assertTrue(out.isEmpty(), "DEPARTED/非成员失读权");
        verify(entryMapper, never()).listActiveForRecall(anyList());
    }

    // ===== 4 混合：部分 ACTIVE 部分非成员 → 只查 ACTIVE 项目 =====

    @Test
    void mixedScope_onlyActiveProjectsQueried() {
        when(memberMapper.selectList(any())).thenReturn(List.of(
                membership(10L, 1L, "ACTIVE")));  // 20L 非成员不返
        when(entryMapper.listActiveForRecall(List.of(10L))).thenReturn(List.of(entry(1, 10L)));

        List<MemoryProjectEntryVO> out = service.collectActiveEntries(List.of(10L, 20L), 1L);

        assertEquals(1, out.size());
        assertEquals(10L, out.get(0).getProjectId());
        verify(entryMapper).listActiveForRecall(List.of(10L));
    }

    // ===== 5 重复 projectId 去重 =====

    @Test
    void duplicateMemberships_distinctProjectIds() {
        when(memberMapper.selectList(any())).thenReturn(List.of(
                membership(10L, 1L, "ACTIVE"),
                membership(10L, 1L, "ACTIVE")));
        when(entryMapper.listActiveForRecall(List.of(10L))).thenReturn(List.of(entry(1, 10L)));

        List<MemoryProjectEntryVO> out = service.collectActiveEntries(List.of(10L), 1L);

        assertEquals(1, out.size());
        verify(entryMapper).listActiveForRecall(List.of(10L));
    }
}
