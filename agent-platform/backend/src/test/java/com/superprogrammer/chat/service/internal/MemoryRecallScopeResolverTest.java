package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryRecallScopeRequest;
import com.superprogrammer.project.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · D-1 · MemoryRecallScopeResolver 单测（Mockito，无 DB）。
 * <p>
 * 覆盖（对齐设计 §3.3 + plan D 联动点 L2 边界）：
 * <ol>
 *   <li>入参 null → 默认 {个人}（首次无历史兜底）。</li>
 *   <li>null userId 不调 ProjectService（防无意义查询）。</li>
 *   <li>personalOn=false + 空项目 → isEmpty（取消全部空召回，L2 边界）。</li>
 *   <li>项目全可访问 → 保留。</li>
 *   <li>项目部分不可访问 → 滤掉（向量 2 项目成员交集，防越权）。</li>
 *   <li>项目全不可访问 → 空集不报错（L2 边界）。</li>
 *   <li>direction 解析（INPUT/OUTPUT/BOTH + 非法兜底）。</li>
 *   <li>includeDeparted 显式 false。</li>
 *   <li>timeWindow 透传。</li>
 *   <li>全 null 字段 → 等于 defaultPersonalOnly。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemoryRecallScopeResolverTest {

    @Mock
    ProjectService projectService;
    @Mock
    MemoryProjectUserGrantService grantService;

    private MemoryRecallScopeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new MemoryRecallScopeResolver(projectService, grantService);
    }

    private static MemoryRecallScopeRequest req() {
        return new MemoryRecallScopeRequest();
    }

    // ===== 默认兜底 =====

    @Test
    void nullRequest_returnsDefaultPersonalOnly() {
        RecallScope s = resolver.resolve(null, 1L);
        assertTrue(s.personalOn());
        assertTrue(s.projectIds().isEmpty());
        assertEquals(RecallDirection.BOTH, s.direction());
        assertTrue(s.timeWindow().isUnbounded());
        assertTrue(s.includeDeparted());
    }

    @Test
    void nullUserId_returnsDefault_noProjectQuery() {
        RecallScope s = resolver.resolve(null, null);
        assertEquals(RecallScope.defaultPersonalOnly(), s);
        verifyNoInteractions(projectService);
    }

    @Test
    void allNullFields_equalsDefault() {
        RecallScope s = resolver.resolve(req(), 1L);
        assertEquals(RecallScope.defaultPersonalOnly(), s);
    }

    // ===== 空召回（L2 边界：取消全部不报错） =====

    @Test
    void personalOff_noProjects_isEmpty() {
        MemoryRecallScopeRequest r = req();
        r.setPersonalOn(false);
        RecallScope s = resolver.resolve(r, 1L);
        assertFalse(s.personalOn());
        assertTrue(s.projectIds().isEmpty());
        assertTrue(s.isEmpty());
    }

    @Test
    void projectsAllInaccessible_emptyNoThrow() {
        when(projectService.listAccessibleProjectIds(1L)).thenReturn(Collections.emptySet());
        MemoryRecallScopeRequest r = req();
        r.setPersonalOn(false);
        r.setProjectIds(List.of(30L, 40L));
        RecallScope s = resolver.resolve(r, 1L);
        assertTrue(s.projectIds().isEmpty());
        assertTrue(s.isEmpty());
    }

    // ===== 项目可访问性过滤（向量 2） =====

    @Test
    void projectsAllAccessible_kept() {
        when(projectService.listAccessibleProjectIds(1L)).thenReturn(Set.of(10L, 20L));
        MemoryRecallScopeRequest r = req();
        r.setPersonalOn(false);
        r.setProjectIds(List.of(10L, 20L));
        RecallScope s = resolver.resolve(r, 1L);
        assertEquals(List.of(10L, 20L), s.projectIds());
        assertFalse(s.isEmpty());
    }

    @Test
    void projectsPartiallyInaccessible_filteredPreservingOrder() {
        when(projectService.listAccessibleProjectIds(1L)).thenReturn(Set.of(10L));
        MemoryRecallScopeRequest r = req();
        r.setPersonalOn(false);
        r.setProjectIds(List.of(10L, 30L));  // 30 不可访问
        RecallScope s = resolver.resolve(r, 1L);
        assertEquals(List.of(10L), s.projectIds());
    }

    // ===== 记忆二期 P1：个人授权项目即使「不可访问」也保留（防授权形同虚设）=====

    @Test
    void grantedProject_keptEvenIfInaccessible() {
        when(projectService.listAccessibleProjectIds(1L)).thenReturn(Set.of(10L));
        when(grantService.findActiveGrantedProjectIds(1L)).thenReturn(List.of(30L));  // 30 非成员但有授权
        MemoryRecallScopeRequest r = req();
        r.setPersonalOn(false);
        r.setProjectIds(List.of(10L, 30L));
        RecallScope s = resolver.resolve(r, 1L);
        assertEquals(List.of(10L, 30L), s.projectIds(), "可访问 ∪ 已授权均保留");
    }

    // ===== 参数解析 =====

    @Test
    void direction_parsedOrDefault() {
        MemoryRecallScopeRequest r = req();
        r.setDirection("INPUT");
        assertEquals(RecallDirection.INPUT, resolver.resolve(r, 1L).direction());

        r.setDirection("invalid");
        assertEquals(RecallDirection.BOTH, resolver.resolve(r, 1L).direction());
    }

    @Test
    void includeDeparted_falseExplicit() {
        MemoryRecallScopeRequest r = req();
        r.setIncludeDeparted(false);
        assertFalse(resolver.resolve(r, 1L).includeDeparted());
    }

    @Test
    void timeWindow_passedThrough() {
        MemoryRecallScopeRequest r = req();
        r.setRelativeDays(7);
        RecallScope s = resolver.resolve(r, 1L);
        assertEquals(7, s.timeWindow().relativeDays());
        assertFalse(s.timeWindow().isUnbounded());
    }
}
