package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.RecallTagMeta;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · D-2 · MemoryTagAggregator 单测（Mockito，mock mapper，无 DB）。
 * <p>
 * 二期 P1（V67）：turns 纯个人域——项目 scope 聚合（ACL readableAuthors + findProjectRecallTags）
 * 已下线，本类只测个人域聚合 + L2 边界：
 * <ol>
 *   <li>空召回 scope / 个人关 → 空表，不调 mapper。</li>
 *   <li>personalOn → 调 findPersonalRecallTags，结果原样返回。</li>
 *   <li>direction/timeWindow（相对/绝对）透传 mapper。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemoryTagAggregatorTest {

    @Mock
    MemoryTagMapper tagMapper;

    MemoryTagAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new MemoryTagAggregator(tagMapper);
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

    // ===== 空召回（L2 边界） =====

    @Test
    void emptyScope_returnsEmpty_noMapperCall() {
        RecallScope empty = new RecallScope(false, List.of(), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true);
        assertTrue(aggregator.aggregate(empty, 1L).isEmpty());
        verifyNoInteractions(tagMapper);
    }

    @Test
    void personalOff_returnsEmpty_noMapperCall() {
        // 项目 scope（个人关）：二期 P1 turns 纯个人域 → 空表（项目侧由条目合流承担，不走本类）
        RecallScope projectOnly = new RecallScope(false, List.of(10L), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true);
        assertTrue(aggregator.aggregate(projectOnly, 1L).isEmpty());
        verifyNoInteractions(tagMapper);
    }

    // ===== 个人 scope =====

    @Test
    void personalOnly_callsPersonalMapper() {
        when(tagMapper.findPersonalRecallTags(eq(1L), anyString(), any(), any(), any()))
                .thenReturn(List.of(meta(1, 1L), meta(2, 1L)));
        List<RecallTagMeta> r = aggregator.aggregate(RecallScope.defaultPersonalOnly(), 1L);
        assertEquals(2, r.size());
        verify(tagMapper).findPersonalRecallTags(eq(1L), eq("BOTH"), isNull(), isNull(), isNull());
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
