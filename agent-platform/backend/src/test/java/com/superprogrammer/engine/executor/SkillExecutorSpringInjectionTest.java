package com.superprogrammer.engine.executor;

import com.superprogrammer.agent.entity.SkillStep;
import com.superprogrammer.agent.mapper.SkillStepMapper;
import com.superprogrammer.engine.context.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillExecutorSpringInjectionTest {

    @Test
    void springCreatedExecutor_usesRegisteredStepActionHandlers() {
        SkillStep step = new SkillStep();
        step.setId(1L);
        step.setStepOrder(1);
        step.setName("Generate summary");
        step.setAction("LLM_CALL");
        step.setConfig("{}");

        SkillStepMapper mapper = mock(SkillStepMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(step));

        StepActionHandler handler = mock(StepActionHandler.class);
        when(handler.getActionType()).thenReturn("LLM_CALL");
        when(handler.execute(anyString(), any())).thenAnswer(invocation -> {
            ExecutionContext context = invocation.getArgument(1);
            context.addMessage("assistant", "summary");
            return StepResult.ok("summary", 10L);
        });

        new ApplicationContextRunner()
                .withBean(SkillStepMapper.class, () -> mapper)
                .withBean(StepActionHandler.class, () -> handler)
                .withBean(SkillExecutor.class)
                .run(context -> {
                    SkillExecutor executor = context.getBean(SkillExecutor.class);

                    String result = executor.executeSkill(
                            1L,
                            new ExecutionContext(1L, "SKILL", 1L, null));

                    assertEquals("summary", result);
                    verify(handler).execute(anyString(), any());
                });
    }
}
