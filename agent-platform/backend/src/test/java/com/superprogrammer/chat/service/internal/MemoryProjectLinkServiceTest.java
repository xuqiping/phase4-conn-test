package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.superprogrammer.chat.dto.MemoryProjectLinkVO;
import com.superprogrammer.chat.entity.MemoryNotification;
import com.superprogrammer.chat.entity.MemoryProjectLink;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import com.superprogrammer.chat.mapper.MemoryProjectLinkMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.project.entity.Project;
import com.superprogrammer.project.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆二期 P2 · 项目授权状态机单测（FR-101/103）。
 * 核心：发起校验（child owner/自环/parent 存在）、同对防重（PENDING/ACTIVE 409）、
 * 30 天防刷（REJECTED created_at 判）、复活语义、条件 UPDATE 并发 409、撤销权边界、通知落库。
 */
@ExtendWith(MockitoExtension.class)
class MemoryProjectLinkServiceTest {

    @BeforeAll
    static void initMpLambdaCache() {
        // 纯 Mockito 跑 LambdaQueryWrapper 须填 MP lambda 缓存（承 AssetProjectServiceTest 范式）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MemoryProjectLink.class);
        TableInfoHelper.initTableInfo(assistant, MemoryProjectMember.class);
    }

    @Mock private MemoryProjectLinkMapper linkMapper;
    @Mock private MemoryProjectMemberMapper memberMapper;
    @Mock private MemoryNotificationMapper notificationMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private MemorySummaryMapper summaryMapper;

    private MemoryProjectLinkService service;

    @BeforeEach
    void setUp() {
        service = new MemoryProjectLinkService(linkMapper, memberMapper, notificationMapper, projectMapper, summaryMapper);
    }

    private MemoryProjectMember member(String role) {
        MemoryProjectMember m = new MemoryProjectMember();
        m.setRole(role);
        m.setStatus("ACTIVE");
        return m;
    }

    /** 带 projectId 的 ACTIVE 成员（findReadableChildProjectIds 查 myProjects 用）。 */
    private MemoryProjectMember member(Long projectId) {
        MemoryProjectMember m = new MemoryProjectMember();
        m.setProjectId(projectId);
        m.setStatus("ACTIVE");
        return m;
    }

    private Project project(Long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        return p;
    }

    private MemoryProjectLink link(String status) {
        MemoryProjectLink l = new MemoryProjectLink();
        l.setId(9L);
        l.setParentProjectId(1L);
        l.setChildProjectId(2L);
        l.setGrantedBy(100L);
        l.setStatus(status);
        l.setCreatedAt(OffsetDateTime.now());
        return l;
    }

    // ---- 发起（request）----

    // AC-FR-101：child owner 发起成功 → PENDING + 通知 parent managers
    @Test
    void request_success_insertsPendingAndNotifies() {
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));   // child owner 校验
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "父项目"));
        when(projectMapper.selectById(2L)).thenReturn(project(2L, "子项目"));
        when(linkMapper.selectOne(any())).thenReturn(null);                // 无同对活行
        when(memberMapper.selectList(any())).thenReturn(
                java.util.List.of(member("OWNER"), member("ADMIN")));      // parent 两名管理者

        MemoryProjectLinkVO vo = service.request(2L, 1L, 100L);

        assertEquals(MemoryProjectLink.STATUS_PENDING, vo.getStatus());
        verify(linkMapper).insert(any(MemoryProjectLink.class));
        verify(notificationMapper, times(2)).insert(any(MemoryNotification.class));
    }

    // AC-FR-101：非 child owner → 403
    @Test
    void request_nonOwner_forbidden() {
        when(memberMapper.selectOne(any())).thenReturn(member("MEMBER"));
        assertThrows(BusinessException.class, () -> service.request(2L, 1L, 100L));
        verify(linkMapper, never()).insert(any(MemoryProjectLink.class));
    }

    // 自环 → 400（DB CHECK 之外的提前拦截）
    @Test
    void request_selfLoop_badRequest() {
        assertThrows(BusinessException.class, () -> service.request(1L, 1L, 100L));
    }

    // AC-FR-101：同对 PENDING 活行 → 409
    @Test
    void request_existingPending_conflict() {
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "父项目"));
        when(linkMapper.selectOne(any())).thenReturn(link(MemoryProjectLink.STATUS_PENDING));
        assertThrows(BusinessException.class, () -> service.request(2L, 1L, 100L));
        verify(linkMapper, never()).insert(any(MemoryProjectLink.class));
    }

    // AC-FR-101：REJECTED 30 天内重发 → 409 防刷
    @Test
    void request_rejectedWithin30d_conflict() {
        MemoryProjectLink rejected = link(MemoryProjectLink.STATUS_REJECTED);
        rejected.setCreatedAt(OffsetDateTime.now().minusDays(10));
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "父项目"));
        when(linkMapper.selectOne(any())).thenReturn(rejected);
        assertThrows(BusinessException.class, () -> service.request(2L, 1L, 100L));
        verify(linkMapper, never()).insert(any(MemoryProjectLink.class));
    }

    // AC-FR-101：REJECTED 超 30 天 → 同行复活 PENDING（重置时钟，不新增行）
    @Test
    void request_rejectedOver30d_revives() {
        MemoryProjectLink rejected = link(MemoryProjectLink.STATUS_REJECTED);
        rejected.setCreatedAt(OffsetDateTime.now().minusDays(31));
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "父项目"));
        when(projectMapper.selectById(2L)).thenReturn(project(2L, "子项目"));
        when(linkMapper.selectOne(any())).thenReturn(rejected);
        when(linkMapper.update(any(), any())).thenReturn(1);

        MemoryProjectLinkVO vo = service.request(2L, 1L, 100L);

        assertEquals(MemoryProjectLink.STATUS_PENDING, vo.getStatus());
        verify(linkMapper, never()).insert(any(MemoryProjectLink.class));
        verify(linkMapper).update(any(), any());
    }

    // ---- 审批（approve/reject）----

    // AC-FR-101：parent admin 通过 → 条件 UPDATE 落 ACTIVE + 通知发起人
    @Test
    void approve_parentAdmin_activates() {
        when(linkMapper.selectById(9L)).thenReturn(link(MemoryProjectLink.STATUS_PENDING));
        when(memberMapper.selectOne(any())).thenReturn(member("ADMIN"));
        when(linkMapper.update(any(), any())).thenReturn(1);
        when(projectMapper.selectById(any())).thenReturn(project(1L, "P"));

        service.approve(9L, 200L);

        verify(linkMapper).update(any(), any());
        verify(notificationMapper).insert(any(MemoryNotification.class));
    }

    // AC-FR-101：非 parent 管理者审批 → 403
    @Test
    void approve_outsider_forbidden() {
        when(linkMapper.selectById(9L)).thenReturn(link(MemoryProjectLink.STATUS_PENDING));
        when(memberMapper.selectOne(any())).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.approve(9L, 999L));
        verify(linkMapper, never()).update(any(), any());
    }

    // AC-FR-101：并发打不穿——条件 UPDATE 影响 0 行 → 409
    @Test
    void approve_concurrent_conflict() {
        when(linkMapper.selectById(9L)).thenReturn(link(MemoryProjectLink.STATUS_PENDING));
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));
        when(linkMapper.update(any(), any())).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.approve(9L, 200L));
    }

    // AC-FR-101：拒绝 → REJECTED（30 天防刷由 request 端按 created_at 兜底）
    @Test
    void reject_parentOwner_rejected() {
        MemoryProjectLink l = link(MemoryProjectLink.STATUS_PENDING);
        when(linkMapper.selectById(9L)).thenReturn(l);
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));
        when(linkMapper.update(any(), any())).thenReturn(1);
        when(projectMapper.selectById(any())).thenReturn(project(1L, "P"));

        service.reject(9L, 200L);
        assertEquals(MemoryProjectLink.STATUS_REJECTED, l.getStatus());
    }

    // ---- 撤销（revoke · 三期非对称）----

    // 三期：child owner 撤 ACTIVE → 不立即生效，置 revoke_requested_by 待 parent 审批（不 REVOKED、不 STALE）
    @Test
    void revoke_activeByChildOwner_setsRevokeRequestPending() {
        when(linkMapper.selectById(9L)).thenReturn(link(MemoryProjectLink.STATUS_ACTIVE));
        // isOwner(child)=OWNER, isOwnerOrAdmin(parent)=null → child 路径
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"), (MemoryProjectMember) null);
        when(linkMapper.update(any(), any())).thenReturn(1);
        when(memberMapper.selectList(any())).thenReturn(java.util.List.of(member("OWNER"))); // parent 管理者（通知）
        when(projectMapper.selectById(any())).thenReturn(project(1L, "P"));

        service.revoke(9L, 100L);

        verify(linkMapper).update(any(), any());                 // 置 revoke_requested_by
        verify(linkMapper, never()).deleteById(any(Long.class)); // 不软删
        verify(summaryMapper, never()).markProjectSharedStaleByChildEntries(any(), any()); // 不 STALE（仍 ACTIVE）
        verify(notificationMapper).insert(any(MemoryNotification.class)); // 通知 parent 审批
    }

    // 三期：child owner 已挂起申请时再点撤销 → 409
    @Test
    void revoke_activeByChildOwner_alreadyPending_conflict() {
        MemoryProjectLink l = link(MemoryProjectLink.STATUS_ACTIVE);
        l.setRevokeRequestedBy(100L);
        when(linkMapper.selectById(9L)).thenReturn(l);
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"), (MemoryProjectMember) null);
        assertThrows(BusinessException.class, () -> service.revoke(9L, 100L));
    }

    // child owner 取消自己 PENDING → 软删
    @Test
    void revoke_pendingByChildOwner_softDeletes() {
        when(linkMapper.selectById(9L)).thenReturn(link(MemoryProjectLink.STATUS_PENDING));
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));

        service.revoke(9L, 100L);
        verify(linkMapper).deleteById(9L);
    }

    // AC-FR-101：普通成员撤 ACTIVE → 403
    @Test
    void revoke_activeByMember_forbidden() {
        when(linkMapper.selectById(9L)).thenReturn(link(MemoryProjectLink.STATUS_ACTIVE));
        when(memberMapper.selectOne(any())).thenReturn(member("MEMBER"), member("MEMBER"));
        assertThrows(BusinessException.class, () -> service.revoke(9L, 300L));
    }

    // 三期：parent manager 主动撤 ACTIVE → 即时 REVOKED + STALE + 通知 child（不需 child 审核）
    @Test
    void revoke_activeByParentManager_revokedImmediateAndStale() {
        when(linkMapper.selectById(9L)).thenReturn(link(MemoryProjectLink.STATUS_ACTIVE));
        // isOwner(child)=null, isOwnerOrAdmin(parent)=OWNER → parent 即时路径
        when(memberMapper.selectOne(any())).thenReturn((MemoryProjectMember) null, member("OWNER"));
        when(linkMapper.update(any(), any())).thenReturn(1);
        when(projectMapper.selectById(any())).thenReturn(project(1L, "P"));
        when(summaryMapper.markProjectSharedStaleByChildEntries(1L, 2L)).thenReturn(2);

        service.revoke(9L, 200L);

        verify(linkMapper).update(any(), any());
        verify(summaryMapper).markProjectSharedStaleByChildEntries(1L, 2L);
        verify(notificationMapper).insert(any(MemoryNotification.class)); // 通知 child
    }

    // ---- 三期：撤销审批（approveRevoke / rejectRevoke）----

    @Test
    void approveRevoke_parentApproves_revokedAndStale() {
        MemoryProjectLink l = link(MemoryProjectLink.STATUS_ACTIVE);
        l.setRevokeRequestedBy(100L);
        when(linkMapper.selectById(9L)).thenReturn(l);
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));   // parent manager
        when(linkMapper.update(any(), any())).thenReturn(1);
        when(projectMapper.selectById(any())).thenReturn(project(1L, "P"));
        when(summaryMapper.markProjectSharedStaleByChildEntries(1L, 2L)).thenReturn(1);

        service.approveRevoke(9L, 200L);

        verify(linkMapper).update(any(), any());
        verify(summaryMapper).markProjectSharedStaleByChildEntries(1L, 2L);
        verify(notificationMapper).insert(any(MemoryNotification.class)); // 通知申请人
    }

    @Test
    void approveRevoke_noRevokeRequest_conflict() {
        when(linkMapper.selectById(9L)).thenReturn(link(MemoryProjectLink.STATUS_ACTIVE));
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));
        assertThrows(BusinessException.class, () -> service.approveRevoke(9L, 200L));
    }

    @Test
    void rejectRevoke_parentRejects_clearsAndNotifies() {
        MemoryProjectLink l = link(MemoryProjectLink.STATUS_ACTIVE);
        l.setRevokeRequestedBy(100L);
        when(linkMapper.selectById(9L)).thenReturn(l);
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));
        when(linkMapper.update(any(), any())).thenReturn(1);
        when(projectMapper.selectById(any())).thenReturn(project(1L, "P"));

        service.rejectRevoke(9L, 200L);

        verify(linkMapper).update(any(), any());
        verify(summaryMapper, never()).markProjectSharedStaleByChildEntries(any(), any()); // 不 STALE（留 ACTIVE）
        verify(notificationMapper).insert(any(MemoryNotification.class));
    }

    // ---- 三期：撤回撤销申请（withdrawRevokeRequest）----

    @Test
    void withdrawRevokeRequest_childOwner_clears() {
        MemoryProjectLink l = link(MemoryProjectLink.STATUS_ACTIVE);
        l.setRevokeRequestedBy(100L);
        when(linkMapper.selectById(9L)).thenReturn(l);
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));
        when(linkMapper.update(any(), any())).thenReturn(1);

        service.withdrawRevokeRequest(9L, 100L);

        verify(linkMapper).update(any(), any());
    }

    @Test
    void withdrawRevokeRequest_nonOwner_forbidden() {
        MemoryProjectLink l = link(MemoryProjectLink.STATUS_ACTIVE);
        l.setRevokeRequestedBy(100L);
        when(linkMapper.selectById(9L)).thenReturn(l);
        when(memberMapper.selectOne(any())).thenReturn((MemoryProjectMember) null);
        assertThrows(BusinessException.class, () -> service.withdrawRevokeRequest(9L, 300L));
    }

    // ---- 三期：findReadableChildProjectIds（被授权方 parent 成员 → 可读 child 项目集）----

    // userId null → 空（不查库）
    @Test
    void findReadableChildProjectIds_nullUser_empty() {
        assertEquals(java.util.List.of(), service.findReadableChildProjectIds(null));
        verify(memberMapper, never()).selectList(any());
    }

    // userId 是 parent(1) 的 ACTIVE 成员，link ACTIVE child=21 → 返回 [21]
    @Test
    void findReadableChildProjectIds_asParentMember_returnsChildren() {
        when(memberMapper.selectList(any())).thenReturn(java.util.List.of(member(1L)));
        when(linkMapper.findActiveChildIds(java.util.List.of(1L))).thenReturn(java.util.List.of(21L));

        assertEquals(java.util.List.of(21L), service.findReadableChildProjectIds(100L));
    }

    // userId 无任何 ACTIVE 项目 → myProjects 空 → findActiveChildIds 空入参短路（不查 link）
    @Test
    void findReadableChildProjectIds_noProjects_empty() {
        when(memberMapper.selectList(any())).thenReturn(java.util.List.of());

        assertEquals(java.util.List.of(), service.findReadableChildProjectIds(100L));
    }
}
