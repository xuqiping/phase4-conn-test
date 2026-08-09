package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemoryProjectUserGrant;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryProjectUserGrantMapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆二期 P1 · 项目↔个人授权状态机单测（只读召回）。
 * 核心：项目主动授权（owner/admin→ACTIVE）/ 个人申请（PENDING→审批）/ approve / reject /
 * revoke（ACTIVE 双方可撤、PENDING 取消）/ 30 天防刷（REJECTED created_at 判）/ 权边界 403。
 */
@ExtendWith(MockitoExtension.class)
class MemoryProjectUserGrantServiceTest {

    @BeforeAll
    static void initMpLambdaCache() {
        // 纯 Mockito 跑 LambdaQueryWrapper 须填 MP lambda 缓存（承 MemoryProjectLinkServiceTest 范式）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MemoryProjectUserGrant.class);
        TableInfoHelper.initTableInfo(assistant, MemoryProjectMember.class);
    }

    @Mock private MemoryProjectUserGrantMapper grantMapper;
    @Mock private MemoryProjectMemberMapper memberMapper;
    @Mock private MemoryNotificationMapper notificationMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private UserMapper userMapper;

    private MemoryProjectUserGrantService service;

    @BeforeEach
    void setUp() {
        service = new MemoryProjectUserGrantService(grantMapper, memberMapper, notificationMapper, projectMapper, userMapper);
    }

    private MemoryProjectMember member(String role) {
        MemoryProjectMember m = new MemoryProjectMember();
        m.setRole(role);
        m.setStatus("ACTIVE");
        return m;
    }

    private Project project(Long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        return p;
    }

    private User user(Long id, String name) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setUsername(name);
        return u;
    }

    private MemoryProjectUserGrant grant(String initiatedBy, String status) {
        MemoryProjectUserGrant g = new MemoryProjectUserGrant();
        g.setId(9L);
        g.setProjectId(1L);
        g.setUserId(2L);
        g.setInitiatedBy(initiatedBy);
        g.setGrantedBy(100L);
        g.setStatus(status);
        g.setCreatedAt(OffsetDateTime.now());
        return g;
    }

    // ---- grantByProject（项目主动授权 → 立即 ACTIVE）----

    @Test
    void grantByProject_owner_immediatelyActive() {
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "P"));
        when(userMapper.selectById(2L)).thenReturn(user(2L, "张三"));
        when(grantMapper.selectOne(any())).thenReturn(null);
        when(grantMapper.insert(any())).thenAnswer(inv -> {
            ((MemoryProjectUserGrant) inv.getArgument(0)).setId(9L);
            return 1;
        });

        var vo = service.grantByProject(1L, 2L, 100L);

        assertEquals("ACTIVE", vo.getStatus());
        assertEquals("PROJECT", vo.getInitiatedBy());
        verify(grantMapper).insert(any());
        verify(notificationMapper).insert(any());  // 通知被授权人
    }

    @Test
    void grantByProject_nonManager_forbidden() {
        when(memberMapper.selectOne(any())).thenReturn(member("MEMBER"));  // 非 owner/admin
        assertThrows(BusinessException.class, () -> service.grantByProject(1L, 2L, 100L));
        verify(grantMapper, never()).insert(any());
    }

    @Test
    void grantByProject_userNotFound_notFound() {
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "P"));
        when(userMapper.selectById(2L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.grantByProject(1L, 2L, 100L));
    }

    @Test
    void grantByProject_alreadyActive_conflict() {
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "P"));
        when(userMapper.selectById(2L)).thenReturn(user(2L, "张三"));
        when(grantMapper.selectOne(any())).thenReturn(grant("USER", "ACTIVE"));
        assertThrows(BusinessException.class, () -> service.grantByProject(1L, 2L, 100L));
    }

    @Test
    void grantByProject_pendingUserRequest_approvedToActive() {
        // 已有 USER 发起的 PENDING 申请 → 项目授权等同审批通过（PENDING→ACTIVE，不新建行）
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "P"));
        when(userMapper.selectById(2L)).thenReturn(user(2L, "张三"));
        when(grantMapper.selectOne(any())).thenReturn(grant("USER", "PENDING"));
        when(grantMapper.update(any(), any())).thenReturn(1);

        var vo = service.grantByProject(1L, 2L, 100L);

        assertEquals("ACTIVE", vo.getStatus());
        verify(grantMapper, never()).insert(any());  // 复用旧行翻转，不 insert
    }

    // ---- applyByUser（个人申请 → PENDING + 通知项目 owner/admin）----

    @Test
    void applyByUser_pendingAndNotify() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "P"));
        when(grantMapper.selectOne(any())).thenReturn(null);
        when(grantMapper.insert(any())).thenAnswer(inv -> {
            ((MemoryProjectUserGrant) inv.getArgument(0)).setId(9L);
            return 1;
        });
        when(memberMapper.selectList(any())).thenReturn(java.util.List.of());  // 无 manager（不阻断通知循环）

        var vo = service.applyByUser(1L, 100L);

        assertEquals("PENDING", vo.getStatus());
        assertEquals("USER", vo.getInitiatedBy());
        assertEquals(100L, vo.getUserId(), "申请人=本人");
    }

    @Test
    void applyByUser_rejectedWithinCooldown_conflict() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "P"));
        MemoryProjectUserGrant rej = grant("USER", "REJECTED");
        rej.setCreatedAt(OffsetDateTime.now());  // 30 天内
        when(grantMapper.selectOne(any())).thenReturn(rej);
        assertThrows(BusinessException.class, () -> service.applyByUser(1L, 100L));
    }

    @Test
    void applyByUser_active_conflict() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "P"));
        when(grantMapper.selectOne(any())).thenReturn(grant("USER", "ACTIVE"));
        assertThrows(BusinessException.class, () -> service.applyByUser(1L, 100L));
    }

    // ---- approve / reject（项目 owner/admin）----

    @Test
    void approve_manager_pendingToActive() {
        when(grantMapper.selectById(9L)).thenReturn(grant("USER", "PENDING"));
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));
        when(grantMapper.update(any(), any())).thenReturn(1);
        service.approve(9L, 100L);
        verify(notificationMapper).insert(any());
    }

    @Test
    void approve_nonManager_forbidden() {
        when(grantMapper.selectById(9L)).thenReturn(grant("USER", "PENDING"));
        when(memberMapper.selectOne(any())).thenReturn(member("MEMBER"));
        assertThrows(BusinessException.class, () -> service.approve(9L, 100L));
        verify(grantMapper, never()).update(any(), any());
    }

    @Test
    void reject_manager_pendingToRejected() {
        when(grantMapper.selectById(9L)).thenReturn(grant("USER", "PENDING"));
        when(memberMapper.selectOne(any())).thenReturn(member("ADMIN"));
        when(grantMapper.update(any(), any())).thenReturn(1);
        service.reject(9L, 100L);
    }

    // ---- revoke（ACTIVE 双方可撤 / PENDING 申请人或 manager 取消）----

    @Test
    void revoke_active_byProjectManager_revoked() {
        when(grantMapper.selectById(9L)).thenReturn(grant("USER", "ACTIVE"));
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER"));
        when(grantMapper.update(any(), any())).thenReturn(1);
        service.revoke(9L, 100L);  // 项目 owner 撤销
    }

    @Test
    void revoke_active_byGranteeSelf_revoked() {
        MemoryProjectUserGrant g = grant("PROJECT", "ACTIVE");
        g.setUserId(100L);  // 被授权人=操作者本人
        when(grantMapper.selectById(9L)).thenReturn(g);
        when(memberMapper.selectOne(any())).thenReturn(null);  // 非项目成员
        when(grantMapper.update(any(), any())).thenReturn(1);
        service.revoke(9L, 100L);  // 被授权人本人撤销
    }

    @Test
    void revoke_active_byOutsider_forbidden() {
        MemoryProjectUserGrant g = grant("PROJECT", "ACTIVE");
        g.setUserId(2L);  // 不是操作者
        when(grantMapper.selectById(9L)).thenReturn(g);
        when(memberMapper.selectOne(any())).thenReturn(null);  // 非项目成员
        assertThrows(BusinessException.class, () -> service.revoke(9L, 999L));
    }

    @Test
    void revoke_pending_byApplicant_softDeleted() {
        MemoryProjectUserGrant g = grant("USER", "PENDING");
        g.setUserId(100L);  // 申请人=操作者本人
        when(grantMapper.selectById(9L)).thenReturn(g);
        service.revoke(9L, 100L);  // 取消自己的申请（软删）
        verify(grantMapper).deleteById(9L);
        verify(grantMapper, never()).update(any(), any());
    }

    // ---- findActiveGrantedProjectIds（召回取数透传）----

    @Test
    void findActiveGrantedProjectIds_passThrough() {
        when(grantMapper.findActiveGrantedProjectIds(100L)).thenReturn(java.util.List.of(1L, 2L));
        assertEquals(java.util.List.of(1L, 2L), service.findActiveGrantedProjectIds(100L));
    }

    @Test
    void findActiveGrantedProjectIds_nullUser_empty() {
        assertEquals(java.util.List.of(), service.findActiveGrantedProjectIds(null));
        verify(grantMapper, never()).findActiveGrantedProjectIds(any());
    }
}
