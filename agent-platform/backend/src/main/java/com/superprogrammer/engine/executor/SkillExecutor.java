package com.superprogrammer.engine.executor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.agent.entity.SkillStep;
import com.superprogrammer.agent.mapper.SkillStepMapper;
import com.superprogrammer.engine.context.ExecutionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class SkillExecutor {

    private final SkillStepMapper skillStepMapper;
    private List<StepActionHandler> handlers;

    public SkillExecutor(SkillStepMapper skillStepMapper) {
        this.skillStepMapper = skillStepMapper;
    }

    public void setHandlers(List<StepActionHandler> handlers) {
        this.handlers = handlers;
    }

    public String executeSkill(Long skillId, ExecutionContext context) {
        LambdaQueryWrapper<SkillStep> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SkillStep::getSkillId, skillId)
               .eq(SkillStep::getDeleted, 0)
               .orderByAsc(SkillStep::getStepOrder);
        List<SkillStep> steps = skillStepMapper.selectList(wrapper);

        if (steps.isEmpty()) {
            log.warn("Skill[{}]没有步骤", skillId);
            return "技能没有定义执行步骤";
        }

        log.info("开始执行Skill[{}], 共{}个步骤", skillId, steps.size());

        for (SkillStep step : steps) {
            log.info("执行步骤 {}: {} ({})", step.getStepOrder(), step.getName(), step.getAction());

            StepActionHandler handler = findHandler(step.getAction());
            if (handler == null) {
                log.error("未找到动作处理器: {}", step.getAction());
                return "步骤[" + step.getName() + "]失败：不支持的动作类型 " + step.getAction();
            }

            StepResult result = handler.execute(
                    step.getConfig() != null ? step.getConfig() : "{}",
                    context);

            if (!result.isSuccess()) {
                log.error("步骤[{}]执行失败: {}", step.getName(), result.getErrorMessage());
                return "步骤[" + step.getName() + "]失败：" + result.getErrorMessage();
            }

            log.info("步骤[{}]完成, 耗时{}ms", step.getName(), result.getDuration());
        }

        return context.getMessageHistory().isEmpty() ? "执行完成" :
                context.getMessageHistory().get(context.getMessageHistory().size() - 1).getContent();
    }

    private StepActionHandler findHandler(String actionType) {
        if (handlers == null) return null;
        return handlers.stream()
                .filter(h -> h.getActionType().equals(actionType))
                .findFirst()
                .orElse(null);
    }
}
