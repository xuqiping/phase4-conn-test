package com.superprogrammer.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.agent.dto.*;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.AgentGroup;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.entity.SkillStep;
import com.superprogrammer.agent.mapper.AgentGroupMapper;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.mapper.SkillMapper;
import com.superprogrammer.agent.mapper.SkillStepMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentService {

    /**
     * 安全体系 S5 · SEC-FR-027（C8 枚举残点）：Agent 状态白名单（对齐 UserController ALLOWED_STATUS 范式）。
     * 原 updateStatus 裸写 body.get("status") 入库——脏值会破坏 AgentHall/AgentDetail 的
     * DRAFT/PUBLISHED/OFFLINE 三态判定与发布流。
     */
    private static final Set<String> ALLOWED_STATUS = Set.of("DRAFT", "PUBLISHED", "OFFLINE");

    private final AgentGroupMapper agentGroupMapper;
    private final AgentMapper agentMapper;
    private final SkillMapper skillMapper;
    private final SkillService skillService;
    private final AgentPermissionService agentPermissionService;
    private final SkillStepMapper skillStepMapper;

    public List<AgentGroupVO> listGroups() {
        LambdaQueryWrapper<AgentGroup> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(AgentGroup::getSortOrder);
        List<AgentGroup> groups = agentGroupMapper.selectList(wrapper);

        return groups.stream()
                .map(group -> {
                    LambdaQueryWrapper<Agent> countWrapper = new LambdaQueryWrapper<>();
                    countWrapper.eq(Agent::getGroupId, group.getId())
                            .isNull(Agent::getParentId);
                    Long count = agentMapper.selectCount(countWrapper);

                    return AgentGroupVO.builder()
                            .id(group.getId())
                            .name(group.getName())
                            .icon(group.getIcon())
                            .description(group.getDescription())
                            .sortOrder(group.getSortOrder())
                            .agentCount(count)
                            .createdAt(group.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());
    }

    public List<AgentVO> listAgents(Long groupId, String keyword, Long parentId) {
        return listAgents(groupId, keyword, parentId, null, true);
    }

    public List<AgentVO> listAgents(Long groupId, String keyword, Long parentId, Long userId, boolean admin) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        if (parentId != null) {
            wrapper.eq(Agent::getParentId, parentId);
        } else {
            wrapper.isNull(Agent::getParentId);
        }
        wrapper.eq(groupId != null, Agent::getGroupId, groupId)
                .and(keyword != null && !keyword.isBlank(), w ->
                        w.like(Agent::getName, keyword)
                                .or()
                                .like(Agent::getDescription, keyword))
                .orderByDesc(Agent::getCreatedAt);
        List<Agent> agents = agentMapper.selectList(wrapper);
        return agents.stream()
                .filter(agent -> userId == null || agentPermissionService.canUse(agent.getId(), userId, admin))
                .map(this::toAgentVO)
                .collect(Collectors.toList());
    }

    public List<AgentVO> listSubAgents(Long parentId) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Agent::getParentId, parentId)
                .orderByAsc(Agent::getId);
        List<Agent> agents = agentMapper.selectList(wrapper);
        return agents.stream().map(this::toAgentVO).collect(Collectors.toList());
    }

    public AgentDetailVO getAgentDetail(Long agentId) {
        return getAgentDetail(agentId, null, true);
    }

    public AgentDetailVO getAgentDetail(Long agentId, Long userId, boolean admin) {
        if (userId != null && !agentPermissionService.canUse(agentId, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该 Agent");
        }
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent不存在");
        }

        AgentGroup group = agentGroupMapper.selectById(agent.getGroupId());
        String groupName = group != null ? group.getName() : null;
        String parentName = null;
        if (agent.getParentId() != null) {
            Agent parent = agentMapper.selectById(agent.getParentId());
            parentName = parent != null ? parent.getName() : null;
        }

        boolean isLeaf = !hasSubAgents(agentId);

        AgentDetailVO.AgentDetailVOBuilder builder = AgentDetailVO.builder()
                .id(agent.getId())
                .name(agent.getName())
                .description(agent.getDescription())
                .avatar(agent.getAvatar())
                .status(agent.getStatus())
                .config(agent.getConfig())
                .groupId(agent.getGroupId())
                .groupName(groupName)
                .parentId(agent.getParentId())
                .parentName(parentName)
                .isLeaf(isLeaf)
                .createdAt(agent.getCreatedAt())
                .updatedAt(agent.getUpdatedAt());

        if (isLeaf) {
            builder.skills(skillService.listByAgentId(agentId));
        } else {
            builder.subAgents(listSubAgents(agentId));
        }

        AgentDetailVO detail = builder.build();
        if (userId != null && !agentPermissionService.canReadPrompt(agentId, userId, admin)) {
            detail.setConfig(null);
        }
        return detail;
    }

    public SkillDetailVO getSkillDetail(Long skillId) {
        return skillService.getDetail(skillId);
    }

    public SkillDetailVO getSkillDetail(Long skillId, Long userId, boolean admin) {
        SkillDetailVO detail = skillService.getDetail(skillId);
        Long agentId = detail.getAgentId();
        if (agentId != null && !agentPermissionService.canUse(agentId, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该能力");
        }
        if (agentId != null && !agentPermissionService.canReadPrompt(agentId, userId, admin)) {
            redactSkillDetail(detail);
        }
        return detail;
    }

    public AgentDetailVO copyAgent(Long sourceAgentId, AgentCopyRequest request, Long userId, boolean admin) {
        if (!agentPermissionService.canCopy(sourceAgentId, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权复制该 Agent");
        }
        Agent source = agentMapper.selectById(sourceAgentId);
        if (source == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent不存在");
        }

        Agent copy = new Agent();
        copy.setName(valueOrDefault(request == null ? null : request.getName(), source.getName() + " Copy"));
        copy.setDescription(valueOrDefault(request == null ? null : request.getDescription(), source.getDescription()));
        copy.setAvatar(valueOrDefault(request == null ? null : request.getAvatar(), source.getAvatar()));
        copy.setGroupId(request != null && request.getGroupId() != null ? request.getGroupId() : source.getGroupId());
        copy.setStatus("DRAFT");
        copy.setConfig(valueOrDefault(request == null ? null : request.getConfig(), source.getConfig()));
        copy.setParentId(source.getParentId());
        copy.setCreatedBy(userId);
        copy.setUpdatedBy(userId);
        agentMapper.insert(copy);

        copySourceSkills(sourceAgentId, copy.getId(), userId);
        return AgentDetailVO.builder()
                .id(copy.getId())
                .name(copy.getName())
                .description(copy.getDescription())
                .avatar(copy.getAvatar())
                .status(copy.getStatus())
                .config(copy.getConfig())
                .groupId(copy.getGroupId())
                .createdAt(copy.getCreatedAt())
                .updatedAt(copy.getUpdatedAt())
                .build();
    }

    private void copySourceSkills(Long sourceAgentId, Long targetAgentId, Long userId) {
        LambdaQueryWrapper<Skill> skillWrapper = new LambdaQueryWrapper<>();
        skillWrapper.eq(Skill::getAgentId, sourceAgentId)
                .eq(Skill::getDeleted, 0)
                .orderByAsc(Skill::getSortOrder);
        for (Skill sourceSkill : skillMapper.selectList(skillWrapper)) {
            Skill copiedSkill = new Skill();
            copiedSkill.setAgentId(targetAgentId);
            copiedSkill.setName(sourceSkill.getName());
            copiedSkill.setDescription(sourceSkill.getDescription());
            copiedSkill.setType(sourceSkill.getType());
            copiedSkill.setConfig(sourceSkill.getConfig());
            copiedSkill.setSortOrder(sourceSkill.getSortOrder());
            copiedSkill.setCreatedBy(userId);
            copiedSkill.setUpdatedBy(userId);
            skillMapper.insert(copiedSkill);
            copySourceSteps(sourceSkill.getId(), copiedSkill.getId(), userId);
        }
    }

    private void copySourceSteps(Long sourceSkillId, Long targetSkillId, Long userId) {
        LambdaQueryWrapper<SkillStep> stepWrapper = new LambdaQueryWrapper<>();
        stepWrapper.eq(SkillStep::getSkillId, sourceSkillId)
                .eq(SkillStep::getDeleted, 0)
                .orderByAsc(SkillStep::getStepOrder);
        for (SkillStep sourceStep : skillStepMapper.selectList(stepWrapper)) {
            SkillStep copiedStep = new SkillStep();
            copiedStep.setSkillId(targetSkillId);
            copiedStep.setStepOrder(sourceStep.getStepOrder());
            copiedStep.setName(sourceStep.getName());
            copiedStep.setAction(sourceStep.getAction());
            copiedStep.setConfig(sourceStep.getConfig());
            copiedStep.setCreatedBy(userId);
            copiedStep.setUpdatedBy(userId);
            skillStepMapper.insert(copiedStep);
        }
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void redactSkillDetail(SkillDetailVO detail) {
        detail.setConfig(null);
        if (detail.getSteps() == null) {
            return;
        }
        detail.getSteps().forEach(step -> step.setConfig(null));
    }

    public AgentVO createAgent(Agent agent) {
        if (agent.getParentId() != null) {
            Agent parent = agentMapper.selectById(agent.getParentId());
            if (parent == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "父Agent不存在");
            }
            if (parent.getParentId() != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持3层嵌套，子Agent只能挂在顶层Agent下");
            }
            if (agent.getGroupId() == null) {
                agent.setGroupId(parent.getGroupId());
            }
        }
        agentMapper.insert(agent);
        return toAgentVO(agent);
    }

    public AgentVO updateAgent(Agent agent) {
        Agent existing = agentMapper.selectById(agent.getId());
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent不存在");
        }
        if (agent.getName() != null) existing.setName(agent.getName());
        if (agent.getDescription() != null) existing.setDescription(agent.getDescription());
        if (agent.getAvatar() != null) existing.setAvatar(agent.getAvatar());
        if (agent.getGroupId() != null) existing.setGroupId(agent.getGroupId());
        if (agent.getUpdatedBy() != null) existing.setUpdatedBy(agent.getUpdatedBy());
        agentMapper.updateById(existing);
        return toAgentVO(existing);
    }

    public void deleteAgent(Long id) {
        Agent agent = agentMapper.selectById(id);
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent不存在");
        }
        // 级联删除子Agent及其技能
        List<Agent> children = agentMapper.selectList(
                new LambdaQueryWrapper<Agent>().eq(Agent::getParentId, id));
        for (Agent child : children) {
            skillService.deleteByAgentId(child.getId());
            agentMapper.deleteById(child.getId());
        }
        // 删除自身技能
        skillService.deleteByAgentId(id);
        agentMapper.deleteById(id);
    }

    public void updateStatus(Long id, String status, Long operatorId) {
        if (status == null || !ALLOWED_STATUS.contains(status)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "非法状态，仅支持 DRAFT/PUBLISHED/OFFLINE");
        }
        Agent agent = agentMapper.selectById(id);
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent不存在");
        }
        agent.setStatus(status);
        agent.setUpdatedBy(operatorId);
        agentMapper.updateById(agent);
    }

    public boolean hasSubAgents(Long agentId) {
        return agentMapper.selectCount(
                new LambdaQueryWrapper<Agent>().eq(Agent::getParentId, agentId)) > 0;
    }

    private AgentVO toAgentVO(Agent agent) {
        AgentGroup group = agentGroupMapper.selectById(agent.getGroupId());
        String groupName = group != null ? group.getName() : null;
        String parentName = null;
        if (agent.getParentId() != null) {
            Agent parent = agentMapper.selectById(agent.getParentId());
            parentName = parent != null ? parent.getName() : null;
        }

        Long skillCount = skillMapper.selectCount(
                new LambdaQueryWrapper<Skill>().eq(Skill::getAgentId, agent.getId()));
        Long subAgentCount = agentMapper.selectCount(
                new LambdaQueryWrapper<Agent>().eq(Agent::getParentId, agent.getId()));
        boolean isLeaf = agent.getParentId() != null || subAgentCount == 0;

        return AgentVO.builder()
                .id(agent.getId())
                .name(agent.getName())
                .description(agent.getDescription())
                .avatar(agent.getAvatar())
                .status(agent.getStatus())
                .groupId(agent.getGroupId())
                .groupName(groupName)
                .parentId(agent.getParentId())
                .parentName(parentName)
                .skillCount(skillCount.intValue())
                .subAgentCount(subAgentCount.intValue())
                .isLeaf(isLeaf)
                .createdAt(agent.getCreatedAt())
                .build();
    }
}
