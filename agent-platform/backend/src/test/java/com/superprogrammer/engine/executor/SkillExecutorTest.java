package com.superprogrammer.engine.executor;

import com.superprogrammer.agent.entity.SkillStep;
import com.superprogrammer.agent.mapper.SkillStepMapper;
import com.superprogrammer.engine.context.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillExecutorTest {

    @Mock
    private SkillStepMapper skillStepMapper;

    @Mock
    private LlmCallHandler llmCallHandler;

    private SkillExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SkillExecutor(skillStepMapper);
        executor.setHandlers(List.of(llmCallHandler));
    }

    @Test
    void executeSkill_shouldRunStepsInOrder() {
        SkillStep step1 = new SkillStep();
        step1.setId(1L);
        step1.setStepOrder(1);
        step1.setName("理解需求");
        step1.setAction("LLM_CALL");
        step1.setConfig("{\"promptTemplate\":\"理解：{{input}}\",\"outputKey\":\"analysis\"}");

        SkillStep step2 = new SkillStep();
        step2.setId(2L);
        step2.setStepOrder(2);
        step2.setName("生成代码");
        step2.setAction("LLM_CALL");
        step2.setConfig("{\"promptTemplate\":\"根据{{analysis}}生成代码\",\"outputKey\":\"code\"}");

        when(skillStepMapper.selectList(any())).thenReturn(List.of(step1, step2));
        when(llmCallHandler.getActionType()).thenReturn("LLM_CALL");
        when(llmCallHandler.execute(anyString(), any()))
                .thenAnswer(invocation -> {
                    ExecutionContext ctx = invocation.getArgument(1);
                    String output = "分析结果";
                    ctx.addMessage("assistant", output);
                    return StepResult.ok(output, 100L);
                })
                .thenAnswer(invocation -> {
                    ExecutionContext ctx = invocation.getArgument(1);
                    String output = "print('hello')";
                    ctx.addMessage("assistant", output);
                    return StepResult.ok(output, 200L);
                });

        ExecutionContext ctx = new ExecutionContext(1L, "AGENT", 1L, null);
        ctx.getVariableStore().set("input", "写一个hello world");

        String result = executor.executeSkill(1L, ctx);

        assertEquals("print('hello')", result);
        verify(llmCallHandler, times(2)).execute(anyString(), any());
    }

    @Test
    void executeSkill_stepFailure_shouldReturnPartialResult() {
        SkillStep step1 = new SkillStep();
        step1.setId(1L);
        step1.setStepOrder(1);
        step1.setName("失败步骤");
        step1.setAction("LLM_CALL");
        step1.setConfig("{}");

        when(skillStepMapper.selectList(any())).thenReturn(List.of(step1));
        when(llmCallHandler.getActionType()).thenReturn("LLM_CALL");
        when(llmCallHandler.execute(anyString(), any()))
                .thenReturn(StepResult.fail("调用超时"));

        ExecutionContext ctx = new ExecutionContext(1L, "AGENT", 1L, null);
        String result = executor.executeSkill(1L, ctx);

        assertTrue(result.contains("失败"));
    }
}
