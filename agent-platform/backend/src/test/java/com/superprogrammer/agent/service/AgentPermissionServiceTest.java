package com.superprogrammer.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.agent.dto.AgentAccessVO;
import com.superprogrammer.agent.dto.AgentPermissionSaveRequest;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.AgentPermission;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.mapper.AgentPermissionMapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentPermissionServiceTest {

    @Mock
    private AgentPermissionMapper permissionMapper;

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private UserMapper userMapper;

    private AgentPermissionService service;

    @BeforeEach
    void setUp() {
        service = new AgentPermissionService(permissionMapper, agentMapper, userMapper);
    }

    @Test
    void resolveAccess_grantsAllPermissionsToAgentOwner() {
        Agent agent = agent(10L, 1L);
        when(agentMapper.selectById(10L)).thenReturn(agent);

        AgentAccessVO access = service.resolveAccess(10L, 1L, false);

        assertTrue(access.getCanManage());
        assertTrue(access.getCanUse());
        assertTrue(access.getCanReadPrompt());
        assertTrue(access.getCanCopy());
        verify(permissionMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void resolveAccess_grantsAllPermissionsToAdmin() {
        Agent agent = agent(10L, 99L);
        when(agentMapper.selectById(10L)).thenReturn(agent);

        AgentAccessVO access = service.resolveAccess(10L, 1L, true);

        assertTrue(access.getCanManage());
        assertTrue(access.getCanUse());
        assertTrue(access.getCanReadPrompt());
        assertTrue(access.getCanCopy());
    }

    @Test
    void resolveAccess_treatsReadPromptAsUsePermission() {
        Agent agent = agent(10L, 99L);
        AgentPermission permission = permission(10L, 1L, false, true, false);
        when(agentMapper.selectById(10L)).thenReturn(agent);
        when(permissionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(permission);

        AgentAccessVO access = service.resolveAccess(10L, 1L, false);

        assertFalse(access.getCanManage());
        assertTrue(access.getCanUse());
        assertTrue(access.getCanReadPrompt());
        assertFalse(access.getCanCopy());
    }

    @Test
    void resolveAccess_treatsCopyAsUsePermission() {
        Agent agent = agent(10L, 99L);
        AgentPermission permission = permission(10L, 1L, false, false, true);
        when(agentMapper.selectById(10L)).thenReturn(agent);
        when(permissionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(permission);

        AgentAccessVO access = service.resolveAccess(10L, 1L, false);

        assertFalse(access.getCanManage());
        assertTrue(access.getCanUse());
        assertFalse(access.getCanReadPrompt());
        assertTrue(access.getCanCopy());
    }

    @Test
    void savePermissions_rejectsNonOwner() {
        Agent agent = agent(10L, 99L);
        when(agentMapper.selectById(10L)).thenReturn(agent);

        AgentPermissionSaveRequest request = new AgentPermissionSaveRequest();
        request.setUserId(2L);
        request.setCanUse(true);

        assertThrows(BusinessException.class,
                () -> service.savePermissions(10L, List.of(request), 1L, false));

        verify(permissionMapper, never()).insert(any(AgentPermission.class));
    }

    @Test
    void savePermissions_ownerUpsertsPermissionAndNormalizesUseFlag() {
        Agent agent = agent(10L, 1L);
        User user = new User();
        user.setId(2L);
        user.setUsername("alice");
        when(agentMapper.selectById(10L)).thenReturn(agent);
        when(userMapper.selectById(2L)).thenReturn(user);
        when(permissionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        AgentPermissionSaveRequest request = new AgentPermissionSaveRequest();
        request.setUserId(2L);
        request.setCanUse(false);
        request.setCanReadPrompt(true);
        request.setCanCopy(false);

        service.savePermissions(10L, List.of(request), 1L, false);

        ArgumentCaptor<AgentPermission> captor = ArgumentCaptor.forClass(AgentPermission.class);
        verify(permissionMapper).insert(captor.capture());
        AgentPermission saved = captor.getValue();
        assertEquals(10L, saved.getAgentId());
        assertEquals(2L, saved.getUserId());
        assertTrue(saved.getCanUse());
        assertTrue(saved.getCanReadPrompt());
        assertFalse(saved.getCanCopy());
        assertEquals(1L, saved.getGrantedBy());
    }

    private Agent agent(Long id, Long ownerId) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setCreatedBy(ownerId);
        return agent;
    }

    private AgentPermission permission(Long agentId, Long userId, boolean canUse, boolean canReadPrompt, boolean canCopy) {
        AgentPermission permission = new AgentPermission();
        permission.setAgentId(agentId);
        permission.setUserId(userId);
        permission.setCanUse(canUse);
        permission.setCanReadPrompt(canReadPrompt);
        permission.setCanCopy(canCopy);
        return permission;
    }
}
