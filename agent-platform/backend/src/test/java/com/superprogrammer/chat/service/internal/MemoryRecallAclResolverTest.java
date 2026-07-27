package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryRecallAclMapper;
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
 * 计划12 · 迭代 I1 · MemoryRecallAclResolver 五路径单测（Mockito，无 DB 实依赖）。
 * <p>
 * 覆盖（对齐 I1 plan 出口条件）：
 * <ol>
 *   <li>owner → 全员（含 DEPARTED），不走 ACL 查询。</li>
 *   <li>admin（recall_admin=false）→ ACL 集 ∪ 自己。</li>
 *   <li>member → ACL 集 ∪ 自己。</li>
 *   <li>recall_admin=true admin → 仍 ACL 集 ∪ 自己（契约：recall_admin 不扩读，只多配权）。</li>
 *   <li>DEPARTED 曾赋权 target → 保留在结果集（保交接，L10 在 I3 过滤）。</li>
 * </ol>
 * 附：owner 无 ACL 行仍全读 / 非成员空集 / 入参 null 空集。
 */
@ExtendWith(MockitoExtension.class)
class MemoryRecallAclResolverTest {

    @Mock MemoryProjectMemberMapper memberMapper;
    @Mock MemoryRecallAclMapper recallAclMapper;

    private MemoryRecallAclResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new MemoryRecallAclResolver(memberMapper, recallAclMapper);
    }

    private MemoryProjectMember member(long userId, String role, Boolean recallAdmin) {
        MemoryProjectMember m = new MemoryProjectMember();
        m.setProjectId(100L);
        m.setUserId(userId);
        m.setRole(role);
        m.setRecallAdmin(recallAdmin);
        m.setStatus("ACTIVE");
        return m;
    }

    // ===== 路径① owner 兜底全读 =====

    @Test
    void owner_returnsAllMembers_noAclQuery() {
        when(memberMapper.selectOne(any())).thenReturn(member(1L, "OWNER", false));
        when(memberMapper.selectList(any())).thenReturn(List.of(
                member(1L, "OWNER", false),
                member(2L, "ADMIN", false),
                member(3L, "MEMBER", false)));

        Set<Long> authors = resolver.readableAuthors(100L, 1L);

        assertEquals(Set.of(1L, 2L, 3L), authors);
        verify(recallAclMapper, never()).findGrantedTargetIds(anyLong(), anyLong());
    }

    @Test
    void owner_withZeroAclRows_stillReturnsAllMembers() {
        // owner 兜底：无 ACL 行也能全读（plan 出口条件）
        when(memberMapper.selectOne(any())).thenReturn(member(1L, "OWNER", false));
        when(memberMapper.selectList(any())).thenReturn(List.of(member(1L, "OWNER", false)));

        Set<Long> authors = resolver.readableAuthors(100L, 1L);

        assertEquals(Set.of(1L), authors);
        verify(recallAclMapper, never()).findGrantedTargetIds(anyLong(), anyLong());
    }

    @Test
    void owner_includesDepartedMembers() {
        // owner 全员路径含 DEPARTED（保交接，L10 在 I3 接入过滤）
        MemoryProjectMember departed = member(5L, "MEMBER", false);
        departed.setStatus("DEPARTED");
        when(memberMapper.selectOne(any())).thenReturn(member(1L, "OWNER", false));
        when(memberMapper.selectList(any())).thenReturn(List.of(
                member(1L, "OWNER", false), departed));

        Set<Long> authors = resolver.readableAuthors(100L, 1L);

        assertTrue(authors.contains(5L));
    }

    // ===== 路径② admin：ACL 集 ∪ 自己 =====

    @Test
    void admin_usesAclSetPlusSelf() {
        when(memberMapper.selectOne(any())).thenReturn(member(2L, "ADMIN", false));
        when(recallAclMapper.findGrantedTargetIds(100L, 2L)).thenReturn(List.of(10L, 11L));

        Set<Long> authors = resolver.readableAuthors(100L, 2L);

        assertEquals(Set.of(2L, 10L, 11L), authors);
    }

    @Test
    void admin_emptyAcl_returnsOnlySelf() {
        when(memberMapper.selectOne(any())).thenReturn(member(2L, "ADMIN", false));
        when(recallAclMapper.findGrantedTargetIds(100L, 2L)).thenReturn(List.of());

        Set<Long> authors = resolver.readableAuthors(100L, 2L);

        assertEquals(Set.of(2L), authors);  // 无授权也能读自己
    }

    // ===== 路径③ member：ACL 集 ∪ 自己 =====

    @Test
    void member_usesAclSetPlusSelf() {
        when(memberMapper.selectOne(any())).thenReturn(member(3L, "MEMBER", false));
        when(recallAclMapper.findGrantedTargetIds(100L, 3L)).thenReturn(List.of(10L));

        Set<Long> authors = resolver.readableAuthors(100L, 3L);

        assertEquals(Set.of(3L, 10L), authors);
    }

    // ===== 路径④ recall_admin 契约：只多配权，不扩读 =====

    @Test
    void recallAdmin_doesNotExpandRead_stillAclPlusSelf() {
        // recall_admin=true 的 admin：读路径与普通 admin/member 一致，不返全员
        when(memberMapper.selectOne(any())).thenReturn(member(2L, "ADMIN", true));
        when(recallAclMapper.findGrantedTargetIds(100L, 2L)).thenReturn(List.of(10L));

        Set<Long> authors = resolver.readableAuthors(100L, 2L);

        assertEquals(Set.of(2L, 10L), authors);
        verify(memberMapper, never()).selectList(any());  // 不走 owner 全员路径
    }

    // ===== 路径⑤ DEPARTED 曾赋权 target 保留（ACL 集） =====

    @Test
    void departedGrantedTarget_keptInAclSet() {
        // ACL 表不存 target 状态；曾授权 target 离职后行仍返（保交接，L10 在 I3 接入过滤）
        when(memberMapper.selectOne(any())).thenReturn(member(2L, "ADMIN", false));
        when(recallAclMapper.findGrantedTargetIds(100L, 2L)).thenReturn(List.of(10L, 11L)); // 11 假设已 DEPARTED

        Set<Long> authors = resolver.readableAuthors(100L, 2L);

        assertTrue(authors.contains(11L));  // 不滤 DEPARTED
        assertEquals(Set.of(2L, 10L, 11L), authors);
    }

    // ===== 非成员：空集 =====

    @Test
    void nonMember_returnsEmpty() {
        when(memberMapper.selectOne(any())).thenReturn(null);

        Set<Long> authors = resolver.readableAuthors(100L, 999L);

        assertTrue(authors.isEmpty());
        verify(recallAclMapper, never()).findGrantedTargetIds(anyLong(), anyLong());
    }

    // ===== 入参校验 =====

    @Test
    void nullArgs_returnsEmpty() {
        assertTrue(resolver.readableAuthors(null, 1L).isEmpty());
        assertTrue(resolver.readableAuthors(100L, null).isEmpty());
        verifyNoInteractions(memberMapper, recallAclMapper);
    }
}
