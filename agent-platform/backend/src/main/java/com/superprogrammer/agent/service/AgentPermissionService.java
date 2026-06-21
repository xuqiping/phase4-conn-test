package com.superprogrammer.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.agent.dto.AgentAccessVO;
import com.superprogrammer.agent.dto.AgentPermissionSaveRequest;
import com.superprogrammer.agent.dto.AgentPermissionVO;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.AgentPermission;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.mapper.AgentPermissionMapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentPermissionService {

    private final AgentPermissionMapper permissionMapper;
    private final AgentMapper agentMapper;
    private final UserMapper userMapper;

    public boolean canManage(Long agentId, Long userId, boolean admin) {
        if (admin) {
            ensureAgent(agentId);
            return true;
        }
        Agent agent = ensureAgent(agentId);
        return userId != null && userId.equals(agent.getCreatedBy());
    }

    public boolean canUse(Long agentId, Long userId, boolean admin) {
        return resolveAccess(agentId, userId, admin).getCanUse();
    }

    public boolean canReadPrompt(Long agentId, Long userId, boolean admin) {
        return resolveAccess(agentId, userId, admin).getCanReadPrompt();
    }

    public boolean canCopy(Long agentId, Long userId, boolean admin) {
        return resolveAccess(agentId, userId, admin).getCanCopy();
    }

    public AgentAccessVO resolveAccess(Long agentId, Long userId, boolean admin) {
        Agent agent = ensureAgent(agentId);
        boolean manage = admin || (userId != null && userId.equals(agent.getCreatedBy()));
        if (manage) {
            return AgentAccessVO.builder()
                    .agentId(agentId)
                    .canManage(true)
                    .canUse(true)
                    .canReadPrompt(true)
                    .canCopy(true)
                    .build();
        }

        AgentPermission permission = findPermission(agentId, userId);
        boolean canReadPrompt = permission != null && Boolean.TRUE.equals(permission.getCanReadPrompt());
        boolean canCopy = permission != null && Boolean.TRUE.equals(permission.getCanCopy());
        boolean canUse = permission != null && (Boolean.TRUE.equals(permission.getCanUse()) || canReadPrompt || canCopy);

        return AgentAccessVO.builder()
                .agentId(agentId)
                .canManage(false)
                .canUse(canUse)
                .canReadPrompt(canReadPrompt)
                .canCopy(canCopy)
                .build();
    }

    public List<AgentPermissionVO> listPermissions(Long agentId, Long operatorId, boolean admin) {
        assertManage(agentId, operatorId, admin);
        LambdaQueryWrapper<AgentPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentPermission::getAgentId, agentId)
                .eq(AgentPermission::getDeleted, 0);
        return permissionMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .toList();
    }

    public void savePermissions(Long agentId, List<AgentPermissionSaveRequest> requests, Long operatorId, boolean admin) {
        assertManage(agentId, operatorId, admin);
        if (requests == null) {
            return;
        }
        for (AgentPermissionSaveRequest request : requests) {
            savePermission(agentId, request, operatorId);
        }
    }

    private void savePermission(Long agentId, AgentPermissionSaveRequest request, Long operatorId) {
        if (request == null || request.getUserId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "授权用户不能为空");
        }
        User user = userMapper.selectById(request.getUserId());
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "授权用户不存在");
        }

        AgentPermission permission = findPermission(agentId, request.getUserId());
        boolean canReadPrompt = Boolean.TRUE.equals(request.getCanReadPrompt());
        boolean canCopy = Boolean.TRUE.equals(request.getCanCopy());
        boolean canUse = Boolean.TRUE.equals(request.getCanUse()) || canReadPrompt || canCopy;
        if (permission == null) {
            permission = new AgentPermission();
            permission.setAgentId(agentId);
            permission.setUserId(request.getUserId());
            permission.setCreatedBy(operatorId);
        }
        permission.setCanUse(canUse);
        permission.setCanReadPrompt(canReadPrompt);
        permission.setCanCopy(canCopy);
        permission.setGrantedBy(operatorId);
        permission.setUpdatedBy(operatorId);

        if (permission.getId() == null) {
            permissionMapper.insert(permission);
        } else {
            permissionMapper.updateById(permission);
        }
    }

    private AgentPermissionVO toVO(AgentPermission permission) {
        User user = userMapper.selectById(permission.getUserId());
        return AgentPermissionVO.builder()
                .id(permission.getId())
                .agentId(permission.getAgentId())
                .userId(permission.getUserId())
                .username(user == null ? null : user.getUsername())
                .canUse(permission.getCanUse())
                .canReadPrompt(permission.getCanReadPrompt())
                .canCopy(permission.getCanCopy())
                .grantedBy(permission.getGrantedBy())
                .updatedAt(permission.getUpdatedAt())
                .build();
    }

    private void assertManage(Long agentId, Long userId, boolean admin) {
        if (!canManage(agentId, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或 Agent 创建者可以分发 Agent 权限");
        }
    }

    private Agent ensureAgent(Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent 不存在");
        }
        return agent;
    }

    private AgentPermission findPermission(Long agentId, Long userId) {
        if (agentId == null || userId == null) {
            return null;
        }
        LambdaQueryWrapper<AgentPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentPermission::getAgentId, agentId)
                .eq(AgentPermission::getUserId, userId)
                .eq(AgentPermission::getDeleted, 0);
        return permissionMapper.selectOne(wrapper);
    }
}
