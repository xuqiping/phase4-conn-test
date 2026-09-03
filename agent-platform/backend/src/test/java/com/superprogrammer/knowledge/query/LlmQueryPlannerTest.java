package com.superprogrammer.knowledge.query;

import com.superprogrammer.knowledge.config.LlmQueryPlannerProperties;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LLM QueryPlanner（WP2 Step4）：正常规划+护栏 / 超时回退 / JSON 解析失败回退 / 开关关零调用。
 */
@ExtendWith(MockitoExtension.class)
class LlmQueryPlannerTest {

    @Mock private LlmGateway llmGateway;
    private final QueryPlanner rulePlanner = new QueryPlanner();
    private LlmQueryPlannerProperties props;

    private LlmQueryPlanner planner;

    @BeforeEach
    void setUp() {
        props = new LlmQueryPlannerProperties();
        planner = new LlmQueryPlanner(rulePlanner, llmGateway, props);
    }

    @Test
    void normalPlan_overlaysValidatedFields() {
        props.getLlm().setEnabled(true);
        when(llmGateway.chat(any(), eq(7L))).thenReturn(LlmResponse.builder().content("""
                ```json
                {"queryType":"COMPARISON","answerShape":"MULTI_EVIDENCE","strategies":["SPARSE","DENSE","编造策略"],
                 "exhaustive":true,"multiHop":false,"subIntents":["差旅标准","报销流程","这一个绝对超过二十个字符长度的超长子主题用来验证截断逻辑"]}
                ```
                """).build());

        var out = planner.planWithFallback("对比 V2.1 和 V1.0 差旅制度", 7L);

        assertTrue(out.llmUsed());
        assertEquals("COMPARISON", out.plan().queryType());
        assertEquals("MULTI_EVIDENCE", out.plan().answerShape());
        assertTrue(out.plan().exhaustive());
        // 未知策略被滤掉；filters 权威=规则正则（V2.1 出自 query 原文）
        assertEquals(List.of("SPARSE", "DENSE"), out.plan().strategies());
        assertEquals("V2.1", out.plan().filters().get("version"));
        // 子意图 ≤3 且每条 ≤20 字（超长被丢）
        assertEquals(List.of("差旅标准", "报销流程"), out.subIntents());
        // 计费归户当前用户 + 超时/禁思考参数
        ArgumentCaptor<LlmRequest> cap = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmGateway).chat(cap.capture(), eq(7L));
        assertEquals(2000, cap.getValue().getTimeoutMs());
        assertEquals(Boolean.TRUE, cap.getValue().getDisableThinking());
    }

    /** C7 GLOBAL（WP4 Step2）：LLM 分类 GLOBAL 进白名单（规则未命中时 LLM 结果优先）。 */
    @Test
    void globalTypeFromLlm_acceptedByWhitelist() {
        props.getLlm().setEnabled(true);
        when(llmGateway.chat(any(), eq(7L))).thenReturn(LlmResponse.builder().content(
                "{\"queryType\":\"GLOBAL\",\"answerShape\":\"OVERVIEW\",\"strategies\":[\"SPARSE\",\"DENSE\"],"
                        + "\"exhaustive\":true,\"multiHop\":false,\"subIntents\":[]}")
                .build());

        var out = planner.planWithFallback("帮我摸一下这个库的底", 7L);   // 规则侧非 GLOBAL（无意图词）

        assertTrue(out.llmUsed());
        assertEquals("GLOBAL", out.plan().queryType());
        assertEquals("OVERVIEW", out.plan().answerShape());
    }

    @Test
    void invalidFields_keepRuleVersion() {
        props.getLlm().setEnabled(true);
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder().content(
                "{\"queryType\":\"胡说\",\"strategies\":[],\"subIntents\":[]}").build());

        var out = planner.planWithFallback("如何安装", 7L);

        assertTrue(out.llmUsed());
        assertEquals("PROCEDURE", out.plan().queryType());   // 非法分类/空策略 → 规则版保留
        assertEquals(rulePlanner.plan("如何安装").strategies(), out.plan().strategies());
        assertTrue(out.subIntents().isEmpty());
    }

    @Test
    void timeout_fallsBackToRule() {
        props.getLlm().setEnabled(true);
        props.getLlm().setTimeoutMs(150);
        when(llmGateway.chat(any(), any())).thenAnswer(inv -> {
            Thread.sleep(2000);   // 远超 150ms 守卫
            return LlmResponse.builder().content("{}").build();
        });

        var out = planner.planWithFallback("如何安装", 7L);

        assertFalse(out.llmUsed());
        assertEquals(rulePlanner.plan("如何安装"), out.plan());
        assertTrue(out.subIntents().isEmpty());
    }

    @Test
    void badJson_fallsBackToRule() {
        props.getLlm().setEnabled(true);
        when(llmGateway.chat(any(), any())).thenReturn(
                LlmResponse.builder().content("这不是 JSON").build());

        var out = planner.planWithFallback("如何安装", 7L);

        assertFalse(out.llmUsed());
        assertEquals(rulePlanner.plan("如何安装"), out.plan());
    }

    @Test
    void disabled_zeroLlmCalls() {
        var out = planner.planWithFallback("如何安装", 7L);   // 默认 enabled=false

        assertFalse(out.llmUsed());
        assertEquals(rulePlanner.plan("如何安装"), out.plan());
        verifyNoInteractions(llmGateway);
    }
}
