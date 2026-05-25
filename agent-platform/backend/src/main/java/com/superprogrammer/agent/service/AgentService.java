// agent-platform/backend/src/main/java/com/superprogrammer/agent/service/AgentService.java
package com.superprogrammer.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.agent.dto.*;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.AgentGroup;
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

    /**
     * 查询所有Agent分组（含每个分组下的Agent数量）
     */
    public List<AgentGroupVO> listGroups() {
        LambdaQueryWrapper<AgentGroup> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(AgentGroup::getSortOrder);
        List<AgentGroup> groups = agentGroupMapper.selectList(wrapper);

        return groups.stream()
                .map(group -> {
                    // 查询分组下的Agent数量
                    LambdaQueryWrapper<Agent> countWrapper = new LambdaQueryWrapper<>();
                    countWrapper.eq(Agent::getGroupId, group.getId());
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

    /**
     * 查询Agent列表（支持按分组和关键词筛选）
     */
    public List<AgentVO> listAgents(Long groupId, String keyword) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(groupId != null, Agent::getGroupId, groupId)
                .and(keyword != null && !keyword.isBlank(), w ->
                        w.like(Agent::getName, keyword)
                                .or()
                                .like(Agent::getDescription, keyword))
                .orderByDesc(Agent::getCreatedAt);
        List<Agent> agents = agentMapper.selectList(wrapper);

        return agents.stream()
                .map(agent -> {
                    // 查询分组名称
                    AgentGroup group = agentGroupMapper.selectById(agent.getGroupId());
                    String groupName = group != null ? group.getName() : null;

                    // 查询技能数量
                    LambdaQueryWrapper<com.superprogrammer.agent.entity.Skill> skillCountWrapper = new LambdaQueryWrapper<>();
                    skillCountWrapper.eq(com.superprogrammer.agent.entity.Skill::getAgentId, agent.getId());
                    Long skillCount = skillMapper.selectCount(skillCountWrapper);

                    return AgentVO.builder()
                            .id(agent.getId())
                            .name(agent.getName())
                            .description(agent.getDescription())
                            .avatar(agent.getAvatar())
                            .status(agent.getStatus())
                            .groupId(agent.getGroupId())
                            .groupName(groupName)
                            .skillCount(skillCount.intValue())
                            .createdAt(agent.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取Agent详情（含技能列表）
     */
    public AgentDetailVO getAgentDetail(Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent不存在");
        }

        AgentGroup group = agentGroupMapper.selectById(agent.getGroupId());
        String groupName = group != null ? group.getName() : null;

        List<SkillVO> skills = skillService.listByAgentId(agentId);

        return AgentDetailVO.builder()
                .id(agent.getId())
                .name(agent.getName())
                .description(agent.getDescription())
                .avatar(agent.getAvatar())
                .status(agent.getStatus())
                .config(agent.getConfig())
                .groupId(agent.getGroupId())
                .groupName(groupName)
                .skills(skills)
                .createdAt(agent.getCreatedAt())
                .updatedAt(agent.getUpdatedAt())
                .build();
    }

    /**
     * 获取技能详情（委托给SkillService）
     */
    public SkillDetailVO getSkillDetail(Long skillId) {
        return skillService.getDetail(skillId);
    }
}
