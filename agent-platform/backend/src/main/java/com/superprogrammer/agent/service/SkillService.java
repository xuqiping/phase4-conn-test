// agent-platform/backend/src/main/java/com/superprogrammer/agent/service/SkillService.java
package com.superprogrammer.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.agent.dto.SkillDetailVO;
import com.superprogrammer.agent.dto.SkillSaveRequest;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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

    @Transactional
    public SkillDetailVO createSkill(Long agentId, SkillSaveRequest request, Long operatorId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent不存在");
        }
        validateRequest(request);

        Skill skill = new Skill();
        skill.setAgentId(agentId);
        applyRequest(skill, request, operatorId);
        skill.setCreatedBy(operatorId);
        skillMapper.insert(skill);
        replaceSteps(skill.getId(), request.getSteps(), operatorId);
        return getDetail(skill.getId());
    }

    @Transactional
    public SkillDetailVO updateSkill(Long skillId, SkillSaveRequest request, Long operatorId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "能力不存在");
        }
        validateRequest(request);

        applyRequest(skill, request, operatorId);
        skillMapper.updateById(skill);
        replaceSteps(skillId, request.getSteps(), operatorId);
        return getDetail(skillId);
    }

    @Transactional
    public void deleteSkill(Long skillId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "能力不存在");
        }
        LambdaQueryWrapper<SkillStep> stepWrapper = new LambdaQueryWrapper<>();
        stepWrapper.eq(SkillStep::getSkillId, skillId);
        skillStepMapper.delete(stepWrapper);
        skillMapper.deleteById(skillId);
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

    private void validateRequest(SkillSaveRequest request) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "能力名称不能为空");
        }
    }

    private void applyRequest(Skill skill, SkillSaveRequest request, Long operatorId) {
        skill.setName(request.getName());
        skill.setDescription(request.getDescription());
        skill.setType(request.getType() == null || request.getType().isBlank() ? "SEQUENCE" : request.getType());
        skill.setConfig(request.getConfig());
        skill.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        skill.setUpdatedBy(operatorId);
    }

    private void replaceSteps(Long skillId, List<SkillSaveRequest.SkillStepSaveRequest> requestSteps, Long operatorId) {
        LambdaQueryWrapper<SkillStep> stepWrapper = new LambdaQueryWrapper<>();
        stepWrapper.eq(SkillStep::getSkillId, skillId);
        skillStepMapper.delete(stepWrapper);

        List<SkillSaveRequest.SkillStepSaveRequest> steps =
                requestSteps == null ? new ArrayList<>() : requestSteps;
        int fallbackOrder = 1;
        for (SkillSaveRequest.SkillStepSaveRequest requestStep : steps) {
            if (requestStep.getName() == null || requestStep.getName().isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "步骤名称不能为空");
            }
            SkillStep step = new SkillStep();
            step.setSkillId(skillId);
            step.setStepOrder(requestStep.getStepOrder() == null ? fallbackOrder : requestStep.getStepOrder());
            step.setName(requestStep.getName());
            step.setAction(requestStep.getAction() == null || requestStep.getAction().isBlank()
                    ? "LLM_CALL"
                    : requestStep.getAction());
            step.setConfig(requestStep.getConfig());
            step.setCreatedBy(operatorId);
            step.setUpdatedBy(operatorId);
            skillStepMapper.insert(step);
            fallbackOrder++;
        }
    }
}
