// agent-platform/backend/src/main/java/com/superprogrammer/agent/service/SkillService.java
package com.superprogrammer.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.agent.dto.SkillDetailVO;
import com.superprogrammer.agent.dto.SkillVO;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.entity.SkillStep;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.mapper.SkillMapper;
import com.superprogrammer.agent.mapper.SkillStepMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SkillService {

    private final SkillMapper skillMapper;
    private final SkillStepMapper skillStepMapper;
    private final AgentMapper agentMapper;

    /**
     * 查询指定Agent下的技能列表
     */
    public List<SkillVO> listByAgentId(Long agentId) {
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Skill::getAgentId, agentId)
                .orderByAsc(Skill::getSortOrder);
        List<Skill> skills = skillMapper.selectList(wrapper);
        return skills.stream()
                .map(this::toSkillVO)
                .collect(Collectors.toList());
    }

    /**
     * 获取技能详情（含步骤）
     */
    public SkillDetailVO getDetail(Long skillId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "技能不存在");
        }

        // 查询所属Agent
        Agent agent = agentMapper.selectById(skill.getAgentId());
        String agentName = agent != null ? agent.getName() : null;

        // 查询步骤
        LambdaQueryWrapper<SkillStep> stepWrapper = new LambdaQueryWrapper<>();
        stepWrapper.eq(SkillStep::getSkillId, skillId)
                .orderByAsc(SkillStep::getStepOrder);
        List<SkillStep> steps = skillStepMapper.selectList(stepWrapper);

        List<SkillDetailVO.SkillStepVO> stepVOs = steps.stream()
                .map(step -> SkillDetailVO.SkillStepVO.builder()
                        .id(step.getId())
                        .stepOrder(step.getStepOrder())
                        .name(step.getName())
                        .action(step.getAction())
                        .config(step.getConfig())
                        .build())
                .collect(Collectors.toList());

        return SkillDetailVO.builder()
                .id(skill.getId())
                .agentId(skill.getAgentId())
                .agentName(agentName)
                .name(skill.getName())
                .description(skill.getDescription())
                .type(skill.getType())
                .config(skill.getConfig())
                .sortOrder(skill.getSortOrder())
                .steps(stepVOs)
                .createdAt(skill.getCreatedAt())
                .updatedAt(skill.getUpdatedAt())
                .build();
    }

    public void deleteByAgentId(Long agentId) {
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Skill::getAgentId, agentId);
        List<Skill> skills = skillMapper.selectList(wrapper);
        for (Skill skill : skills) {
            LambdaQueryWrapper<SkillStep> stepWrapper = new LambdaQueryWrapper<>();
            stepWrapper.eq(SkillStep::getSkillId, skill.getId());
            skillStepMapper.delete(stepWrapper);
            skillMapper.deleteById(skill.getId());
        }
    }

    private SkillVO toSkillVO(Skill skill) {
        return SkillVO.builder()
                .id(skill.getId())
                .name(skill.getName())
                .description(skill.getDescription())
                .type(skill.getType())
                .sortOrder(skill.getSortOrder())
                .createdAt(skill.getCreatedAt())
                .build();
    }
}
