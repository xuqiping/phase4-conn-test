package com.superprogrammer.projectgroup.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupJoinRequestEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupJoinRequestMapper;
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 修复IV D2（17x-3，决策 7）：公共池审批通过落成员行 quota=0（原 NULL=不限）——
 * 有界额度不再有「不限额下级」毒化顾虑，预算仍挂组长。
 */
@ExtendWith(MockitoExtension.class)
class ProjectGroupPoolServiceTest {

    private static final long GROUP_ID = 10L;
    private static final long OWNER = 1L;
    private static final long APPLICANT = 9L;
    private static final long REQUEST_ID = 55L;

    @Mock private ProjectGroupJoinRequestMapper requestMapper;
    @Mock private ProjectGroupMapper groupMapper;
    @Mock private ProjectGroupMemberMapper memberMapper;
    @Mock private ProjectGroupService groupService;
    @Mock private UserMapper userMapper;
    @Mock private MemoryNotificationMapper notificationMapper;

    @InjectMocks
    private ProjectGroupPoolService service;

    private ProjectGroupEntity group;
    private ProjectGroupJoinRequestEntity req;

    /** decide 走 LambdaUpdateWrapper，纯 Mockito 环境需先初始化 MP lambda 缓存。 */
    @BeforeAll
    static void initMpLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), ProjectGroupPoolServiceTest.class.getName());
        TableInfoHelper.initTableInfo(assistant, ProjectGroupJoinRequestEntity.class);
    }

    @BeforeEach
    void setUp() {
        group = new ProjectGroupEntity();
        group.setId(GROUP_ID);
        group.setName("测试组");
        group.setOwnerUserId(OWNER);
        group.setDeleted(0);

        req = new ProjectGroupJoinRequestEntity();
        req.setId(REQUEST_ID);
        req.setGroupId(GROUP_ID);
        req.setUserId(APPLICANT);
        req.setStatus(ProjectGroupJoinRequestEntity.STATUS_PENDING);
    }

    @Test
    void 审批通过_落成员行quota为0_预算挂组长() {
        when(requestMapper.selectById(REQUEST_ID)).thenReturn(req);
        when(groupService.requireRole(eq(GROUP_ID), eq(OWNER), eq(false), any())).thenReturn(group);
        when(requestMapper.update(any(), any())).thenReturn(1);
        when(memberMapper.selectByGroupUser(GROUP_ID, APPLICANT)).thenReturn(null);
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);

        assertThatCode(() -> service.decide(REQUEST_ID, OWNER, false, true))
                .doesNotThrowAnyException();

        verify(groupService).insertMemberRow(GROUP_ID, APPLICANT, BigDecimal.ZERO, OWNER);
    }
}
