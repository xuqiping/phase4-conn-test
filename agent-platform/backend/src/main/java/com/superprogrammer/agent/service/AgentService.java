package com.superprogrammer.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.agent.dto.*;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.AgentGroup;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.mapper.AgentGroupMapper;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.mapper.SkillMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentGroupMapper agentGroupMapper;
    private final AgentMapper agentMapper;
    private final SkillMapper skillMapper;
    private final SkillService skillService;

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
        return agents.stream().map(this::toAgentVO).collect(Collectors.toList());
    }

    public List<AgentVO> listSubAgents(Long parentId) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Agent::getParentId, parentId)
                .orderByAsc(Agent::getId);
        List<Agent> agents = agentMapper.selectList(wrapper);
        return agents.stream().map(this::toAgentVO).collect(Collectors.toList());
    }

    public AgentDetailVO getAgentDetail(Long agentId) {
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

        return builder.build();
    }

    public SkillDetailVO getSkillDetail(Long skillId) {
        return skillService.getDetail(skillId);
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
