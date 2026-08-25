package com.superprogrammer.projectgroup.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupInviteEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupInviteMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 17x-1 次生洞收口（A3）：PENDING 邀请接受时 allocated_by 归属解析——
 * 邀请人已降职/被移除/组外 admin 代发 → 落行改挂组长；仍在任 MANAGER → 归其预算。
 */
@ExtendWith(MockitoExtension.class)
class ProjectGroupInviteServiceTest {

    private static final long GROUP_ID = 10L;
    private static final long OWNER = 1L;
    private static final long MANAGER_UID = 4L;
    private static final long INVITEE = 9L;

    @Mock private ProjectGroupInviteMapper inviteMapper;
    @Mock private ProjectGroupMapper groupMapper;
    @Mock private ProjectGroupMemberMapper memberMapper;
    @Mock private ProjectGroupService groupService;
    @Mock private UserMapper userMapper;
    @Mock private MemoryNotificationMapper notificationMapper;
    @Mock private MemberBudgetService budgetService;

    @InjectMocks
    private ProjectGroupInviteService service;

    private ProjectGroupEntity group;
    private ProjectGroupInviteEntity invite;

    /** transition/revoke 走 LambdaUpdateWrapper，纯 Mockito 环境需先初始化 MP lambda 缓存。 */
    @BeforeAll
    static void initMpLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), ProjectGroupInviteServiceTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, ProjectGroupInviteEntity.class);
    }

    @BeforeEach
    void setUp() {
        group = new ProjectGroupEntity();
        group.setId(GROUP_ID);
        group.setName("测试组");
        group.setOwnerUserId(OWNER);
        group.setDeleted(0);

        invite = new ProjectGroupInviteEntity();
        invite.setId(77L);
        invite.setGroupId(GROUP_ID);
        invite.setInviterUserId(MANAGER_UID);
        invite.setInviteeUserId(INVITEE);
        invite.setQuotaLimitPoints(BigDecimal.TEN);
        invite.setStatus(ProjectGroupInviteEntity.STATUS_PENDING);
    }

    /** 接受翻转条件 UPDATE 打穿（返回 1=成功）。 */
    private void stubTransitionOk() {
        when(inviteMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void 接受_邀请人仍在任MANAGER_落行归其预算() {
        when(inviteMapper.selectById(77L)).thenReturn(invite);
        stubTransitionOk();
        when(memberMapper.selectByGroupUser(GROUP_ID, INVITEE)).thenReturn(null);
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        ProjectGroupMemberEntity mgrRow = new ProjectGroupMemberEntity();
        mgrRow.setRole(ProjectGroupMemberEntity.ROLE_MANAGER);
        when(memberMapper.selectByGroupUser(GROUP_ID, MANAGER_UID)).thenReturn(mgrRow);

        assertThatCode(() -> service.accept(77L, INVITEE)).doesNotThrowAnyException();

        verify(groupService).insertMemberRow(GROUP_ID, INVITEE, BigDecimal.TEN, MANAGER_UID);
    }

    @Test
    void 接受_邀请人已降职为MEMBER_落行改挂组长() {
        when(inviteMapper.selectById(77L)).thenReturn(invite);
        stubTransitionOk();
        when(memberMapper.selectByGroupUser(GROUP_ID, INVITEE)).thenReturn(null);
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        ProjectGroupMemberEntity demoted = new ProjectGroupMemberEntity();
        demoted.setRole(ProjectGroupMemberEntity.ROLE_MEMBER);
        when(memberMapper.selectByGroupUser(GROUP_ID, MANAGER_UID)).thenReturn(demoted);

        assertThatCode(() -> service.accept(77L, INVITEE)).doesNotThrowAnyException();

        verify(groupService).insertMemberRow(GROUP_ID, INVITEE, BigDecimal.TEN, OWNER);
    }

    @Test
    void 接受_邀请人已移除行不存在_落行改挂组长() {
        when(inviteMapper.selectById(77L)).thenReturn(invite);
        stubTransitionOk();
        when(memberMapper.selectByGroupUser(GROUP_ID, INVITEE)).thenReturn(null);
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(memberMapper.selectByGroupUser(GROUP_ID, MANAGER_UID)).thenReturn(null);

        assertThatCode(() -> service.accept(77L, INVITEE)).doesNotThrowAnyException();

        verify(groupService).insertMemberRow(GROUP_ID, INVITEE, BigDecimal.TEN, OWNER);
    }

    @Test
    void 接受_邀请人即组长_归组长不查成员行() {
        invite.setInviterUserId(OWNER);
        when(inviteMapper.selectById(77L)).thenReturn(invite);
        stubTransitionOk();
        when(memberMapper.selectByGroupUser(GROUP_ID, INVITEE)).thenReturn(null);
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);

        assertThatCode(() -> service.accept(77L, INVITEE)).doesNotThrowAnyException();

        verify(groupService).insertMemberRow(GROUP_ID, INVITEE, BigDecimal.TEN, OWNER);
        verify(memberMapper, never()).selectByGroupUser(GROUP_ID, OWNER);
    }

    @Test
    void 接受_并发状态已变_409不落行() {
        when(inviteMapper.selectById(77L)).thenReturn(invite);
        when(inviteMapper.update(any(), any())).thenReturn(0);

        assertThatCode(() -> service.accept(77L, INVITEE))
                .isInstanceOf(BusinessException.class);
        org.mockito.Mockito.verify(groupService, never())
                .insertMemberRow(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void 接受_非被邀请人本人_403() {
        when(inviteMapper.selectById(77L)).thenReturn(invite);
        assertThatCode(() -> service.accept(77L, 123L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("本人");
    }
}
