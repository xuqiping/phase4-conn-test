package com.superprogrammer.chat.service;

import com.superprogrammer.agent.dto.AgentVO;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.service.AgentPermissionService;
import com.superprogrammer.agent.service.AgentService;
import com.superprogrammer.auth.security.PermissionEvaluator;
import com.superprogrammer.chat.dto.ChatTargetVO;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.workflow.dto.WorkflowVO;
import com.superprogrammer.workflow.entity.Workflow;
import com.superprogrammer.workflow.mapper.WorkflowMapper;
import com.superprogrammer.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChatTargetService {

    private static final String AGENT_READ = "agent:read";
    private static final String WORKFLOW_READ = "workflow:read";
    private static final String PUBLISHED = "PUBLISHED";

    private final AgentService agentService;
    private final WorkflowService workflowService;
    private final AgentMapper agentMapper;
    private final WorkflowMapper workflowMapper;
    private final PermissionEvaluator permissionEvaluator;
    private final AgentPermissionService agentPermissionService;

    public List<ChatTargetVO> listTargets(Long userId, Authentication authentication) {
        List<ChatTargetVO> targets = new ArrayList<>();
        targets.add(ChatTargetVO.builder()
                .type("NONE")
                .targetKey("none")
                .name("无")
                .description("不绑定工作流或智能体")
                .available(true)
                .build());

        agentService.listAgents(null, null, null, userId, isAdmin(authentication)).stream()
                .filter(agent -> PUBLISHED.equalsIgnoreCase(agent.getStatus()))
                .map(this::toAgentTarget)
                .forEach(targets::add);

        if (permissionEvaluator.hasPermission(authentication, WORKFLOW_READ)) {
            workflowService.listWorkflows(userId).stream()
                    .map(this::toWorkflowTarget)
                    .forEach(targets::add);
        }

        return targets;
    }

    public void validateTarget(Long userId, Long agentId, Long workflowId) {
        if (agentId != null && workflowId != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能同时选择智能体和工作流");
        }
        if (agentId != null) {
            validateAgent(userId, agentId);
        }
        if (workflowId != null) {
            validateWorkflow(userId, workflowId);
        }
    }

    private void validateAgent(Long userId, Long agentId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!agentPermissionService.canUse(agentId, userId, isAdmin(authentication))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权使用该 Agent");
        }
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "智能体不存在");
        }
        if (!PUBLISHED.equalsIgnoreCase(agent.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_NOT_PUBLISHED, "智能体未发布");
        }
    }

    private void validateWorkflow(Long userId, Long workflowId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!permissionEvaluator.hasPermission(authentication, WORKFLOW_READ)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权使用工作流");
        }
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流不存在");
        }
        if (!workflow.getOwnerId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权使用该工作流");
        }
    }

    private ChatTargetVO toAgentTarget(AgentVO agent) {
        return ChatTargetVO.builder()
                .type("AGENT")
                .targetKey("agent:" + agent.getId())
                .id(agent.getId())
                .name(agent.getName())
                .description(agent.getDescription())
                .available(true)
                .metadata(metadata("status", agent.getStatus(), "groupName", agent.getGroupName()))
                .build();
    }

    private ChatTargetVO toWorkflowTarget(WorkflowVO workflow) {
        return ChatTargetVO.builder()
                .type("WORKFLOW")
                .targetKey("workflow:" + workflow.getId())
                .id(workflow.getId())
                .name(workflow.getName())
                .description(workflow.getDescription())
                .available(true)
                .metadata(metadata("status", workflow.getStatus(), "ownerId", workflow.getOwnerId()))
                .build();
    }

    private Map<String, Object> metadata(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(key1, value1);
        metadata.put(key2, value2);
        return metadata;
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_admin".equalsIgnoreCase(authority.getAuthority())
                        || "ROLE_ADMIN".equalsIgnoreCase(authority.getAuthority()));
    }
}
