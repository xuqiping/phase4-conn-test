package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 记忆二期 P1 · MemoryRecallAclResolver 简化版单测（五路径 → 单路径成员判定，设计 §5）。
 * <p>
 * 覆盖：
 * <ol>
 *   <li>ACTIVE 成员 → 项目全部成员（含 DEPARTED，保交接由 L10 过滤）。</li>
 *   <li>DEPARTED 读者 → 空集（二期失读权）。</li>
 *   <li>非成员 → 空集。</li>
 *   <li>入参 null → 空集（不查库）。</li>
 *   <li>单人项目 ACTIVE → 仅自己。</li>
 * </ol>
 * 一期五路径（owner 兜底 / ACL 集 / recall_admin）随 recall_acl 表废弃（V67 DROP）。
 */
@ExtendWith(MockitoExtension.class)
class MemoryRecallAclResolverTest {

    @Mock MemoryProjectMemberMapper memberMapper;

    private MemoryRecallAclResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new MemoryRecallAclResolver(memberMapper);
    }

    private MemoryProjectMember member(long userId, String status) {
        MemoryProjectMember m = new MemoryProjectMember();
        m.setProjectId(100L);
        m.setUserId(userId);
        m.setStatus(status);
        return m;
    }

    // ===== ACTIVE 成员 → 全部成员（含 DEPARTED） =====

    @Test
    void activeMember_returnsAllMembers_includingDeparted() {
        when(memberMapper.selectOne(any())).thenReturn(member(1L, "ACTIVE"));
        when(memberMapper.selectList(any())).thenReturn(List.of(
                member(1L, "ACTIVE"),
                member(2L, "ACTIVE"),
                member(3L, "DEPARTED")));

        Set<Long> authors = resolver.readableAuthors(100L, 1L);

        assertEquals(Set.of(1L, 2L, 3L), authors, "ACTIVE 成员可读全员，DEPARTED 保留（L10 层过滤）");
    }

    @Test
    void activeMember_singleMemberProject_returnsOnlySelf() {
        when(memberMapper.selectOne(any())).thenReturn(member(1L, "ACTIVE"));
        when(memberMapper.selectList(any())).thenReturn(List.of(member(1L, "ACTIVE")));

        Set<Long> authors = resolver.readableAuthors(100L, 1L);

        assertEquals(Set.of(1L), authors);
    }

    // ===== DEPARTED 读者 → 空集（失读权） =====

    @Test
    void departedReader_returnsEmpty() {
        when(memberMapper.selectOne(any())).thenReturn(member(1L, "DEPARTED"));

        Set<Long> authors = resolver.readableAuthors(100L, 1L);

        assertTrue(authors.isEmpty(), "DEPARTED 失读权（二期 §8.1）");
        verify(memberMapper, never()).selectList(any());
    }

    // ===== 非成员 → 空集 =====

    @Test
    void nonMember_returnsEmpty() {
        when(memberMapper.selectOne(any())).thenReturn(null);

        Set<Long> authors = resolver.readableAuthors(100L, 999L);

        assertTrue(authors.isEmpty());
        verify(memberMapper, never()).selectList(any());
    }

    // ===== 入参 null → 空集，不查库 =====

    @Test
    void nullArgs_returnsEmpty() {
        assertTrue(resolver.readableAuthors(null, 1L).isEmpty());
        assertTrue(resolver.readableAuthors(100L, null).isEmpty());
        verifyNoInteractions(memberMapper);
    }
}
