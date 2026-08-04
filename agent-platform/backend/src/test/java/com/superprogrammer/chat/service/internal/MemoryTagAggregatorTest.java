package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.RecallTagMeta;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · D-2 · MemoryTagAggregator 单测（Mockito，mock mapper + aclResolver，无 DB）。
 * <p>
 * 覆盖（对齐 §3.3 ② + §6 向量 3/14 + L2 边界）：
 * <ol>
 *   <li>空召回 scope → 空表，不调 mapper。</li>
 *   <li>personalOn only → 调 findPersonalRecallTags。</li>
 *   <li>项目 + readableAuthors 非空 → 调 findProjectRecallTags。</li>
 *   <li>项目 + readableAuthors 空集 → skip（向量 14 防越权，不调 mapper）。</li>
 *   <li>个人 + 项目同 tag_id → 去重 by id（size=1）。</li>
 *   <li>direction/timeWindow 透传 mapper。</li>
 *   <li>多项目各查 + 合并。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemoryTagAggregatorTest {

    @Mock
    MemoryTagMapper tagMapper;

    @Mock
    MemoryRecallAclResolver aclResolver;

    @InjectMocks
    MemoryTagAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new MemoryTagAggregator(tagMapper, aclResolver);
    }

    private static RecallTagMeta meta(long id, long owner) {
        RecallTagMeta m = new RecallTagMeta();
        m.setId(id);
        m.setOwnerUserId(owner);
        m.setSubject("我");
        m.setTopic("t" + id);
        m.setLabel("l" + id);
        m.setUsageCount(1);
        return m;
    }

    private static RecallScope personalOnly() {
        return RecallScope.defaultPersonalOnly();
    }

    private static RecallScope projectOnly(Long... pids) {
        return new RecallScope(false, List.of(pids), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true);
    }

    // ===== 空召回（L2 边界） =====

    @Test
    void emptyScope_returnsEmpty_noMapperCall() {
        RecallScope empty = new RecallScope(false, List.of(), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true);
        assertTrue(aggregator.aggregate(empty, 1L).isEmpty());
        verifyNoInteractions(tagMapper);
    }

    // ===== 个人 scope =====

    @Test
    void personalOnly_callsPersonalMapper() {
        when(tagMapper.findPersonalRecallTags(eq(1L), anyString(), any(), any(), any()))
                .thenReturn(List.of(meta(1, 1L), meta(2, 1L)));
        List<RecallTagMeta> r = aggregator.aggregate(personalOnly(), 1L);
        assertEquals(2, r.size());
        verify(tagMapper).findPersonalRecallTags(eq(1L), eq("BOTH"), isNull(), isNull(), isNull());
        verify(tagMapper, never()).findProjectRecallTags(anyLong(), anyLong(), anyList(), anyString(), any(), any(), any());
    }

    // ===== 项目 scope + ACL =====

    @Test
    void project_authorsNonEmpty_callsProjectMapper() {
        when(aclResolver.readableAuthors(10L, 1L)).thenReturn(Set.of(1L, 2L));
        when(tagMapper.findProjectRecallTags(eq(10L), eq(1L), anyList(), anyString(), any(), any(), any()))
                .thenReturn(List.of(meta(3, 2L)));
        List<RecallTagMeta> r = aggregator.aggregate(projectOnly(10L), 1L);
        assertEquals(1, r.size());
        assertEquals(3L, r.get(0).getId());
        // 个人 off → 不调个人 mapper
        verify(tagMapper, never()).findPersonalRecallTags(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    void project_authorsEmpty_skipProjectQuery() {
        when(aclResolver.readableAuthors(10L, 1L)).thenReturn(Set.of());  // 无权
        List<RecallTagMeta> r = aggregator.aggregate(projectOnly(10L), 1L);
        assertTrue(r.isEmpty());
        // 向量14：readableAuthors 空 → skip，不调项目 mapper（防越权）
        verify(tagMapper, never()).findProjectRecallTags(anyLong(), anyLong(), anyList(), anyString(), any(), any(), any());
    }

    @Test
    void multipleProjects_eachQueriedAndMerged() {
        when(aclResolver.readableAuthors(10L, 1L)).thenReturn(Set.of(1L));
        when(aclResolver.readableAuthors(20L, 1L)).thenReturn(Set.of(2L));
        when(tagMapper.findProjectRecallTags(eq(10L), eq(1L), anyList(), anyString(), any(), any(), any()))
                .thenReturn(List.of(meta(3, 1L)));
        when(tagMapper.findProjectRecallTags(eq(20L), eq(1L), anyList(), anyString(), any(), any(), any()))
                .thenReturn(List.of(meta(4, 2L)));
        List<RecallTagMeta> r = aggregator.aggregate(projectOnly(10L, 20L), 1L);
        assertEquals(2, r.size());
    }

    // ===== 去重 =====

    @Test
    void personalAndProject_sameTagId_deduped() {
        // 个人 owner=self 的 tag(id=1) 与自己挂项目的同 tag(id=1) 重复 → 去重一条
        when(tagMapper.findPersonalRecallTags(eq(1L), anyString(), any(), any(), any()))
                .thenReturn(List.of(meta(1, 1L)));
        when(aclResolver.readableAuthors(10L, 1L)).thenReturn(Set.of(1L));
        when(tagMapper.findProjectRecallTags(eq(10L), eq(1L), anyList(), anyString(), any(), any(), any()))
                .thenReturn(List.of(meta(1, 1L)));
        RecallScope scope = new RecallScope(true, List.of(10L), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true);
        List<RecallTagMeta> r = aggregator.aggregate(scope, 1L);
        assertEquals(1, r.size());
    }

    // ===== 参数透传 =====

    @Test
    void directionAndTimeWindow_passedThrough() {
        when(tagMapper.findPersonalRecallTags(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(List.of());
        RecallTimeWindow tw = new RecallTimeWindow(7, null, null);
        RecallScope scope = new RecallScope(true, List.of(), RecallDirection.INPUT, tw, true);
        aggregator.aggregate(scope, 1L);
        verify(tagMapper).findPersonalRecallTags(eq(1L), eq("INPUT"), isNull(), isNull(), eq(7));
    }

    @Test
    void directionAndAbsoluteWindow_passedThrough() {
        OffsetDateTime start = OffsetDateTime.now().minusDays(7);
        OffsetDateTime end = OffsetDateTime.now();
        when(tagMapper.findPersonalRecallTags(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(List.of());
        RecallTimeWindow tw = new RecallTimeWindow(null, start, end);
        RecallScope scope = new RecallScope(true, List.of(), RecallDirection.OUTPUT, tw, false);
        aggregator.aggregate(scope, 1L);
        verify(tagMapper).findPersonalRecallTags(eq(1L), eq("OUTPUT"), eq(start), eq(end), isNull());
    }
}
