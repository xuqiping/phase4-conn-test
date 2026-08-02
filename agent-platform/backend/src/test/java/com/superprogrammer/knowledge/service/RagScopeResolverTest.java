package com.superprogrammer.knowledge.service;

import com.superprogrammer.agent.service.AgentKbBindingService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.workflow.service.WorkflowKbBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
// KnowledgeBase 是 @Data，两实例字段同则 .equals() 相等 → eq() 会跨实例串台，canRead stub 须用 same()（恒等）

/**
 * RagScopeResolver P4 求交（执行身份权限 ∩ 绑定）+ 同模型约束 + mode 派发测。
 * 任一空集/无权限/不存在 → 空集（禁放大）。
 */
@ExtendWith(MockitoExtension.class)
class RagScopeResolverTest {

    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private AgentKbBindingService agentKbBindingService;
    @Mock private WorkflowKbBindingService workflowKbBindingService;

    private RagScopeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RagScopeResolver(knowledgeBaseService, agentKbBindingService, workflowKbBindingService);
    }

    @Test
    void chat_readableBothSameModel_returnsBoth() {
        KnowledgeBase kb1 = kb("doubao-embedding-vision");
        KnowledgeBase kb2 = kb("doubao-embedding-vision");
        when(knowledgeBaseService.ensure(1L)).thenReturn(kb1);
        when(knowledgeBaseService.ensure(2L)).thenReturn(kb2);
        when(knowledgeBaseService.canRead(same(kb1), eq(7L), anyBoolean())).thenReturn(true);
        when(knowledgeBaseService.canRead(same(kb2), eq(7L), anyBoolean())).thenReturn(true);

        List<Long> r = resolver.resolveEffectiveKbs("CHAT", List.of(1L, 2L), null, null, 7L, false);

        assertEquals(List.of(1L, 2L), r);
    }

    @Test
    void p4_unreadableKb_filtered() {
        KnowledgeBase kb1 = kb("m");
        KnowledgeBase kb2 = kb("m");
        when(knowledgeBaseService.ensure(1L)).thenReturn(kb1);
        when(knowledgeBaseService.ensure(2L)).thenReturn(kb2);
        when(knowledgeBaseService.canRead(same(kb1), eq(7L), anyBoolean())).thenReturn(true);
        when(knowledgeBaseService.canRead(same(kb2), eq(7L), anyBoolean())).thenReturn(false);  // 无权限

        assertEquals(List.of(1L), resolver.resolveEffectiveKbs("CHAT", List.of(1L, 2L), null, null, 7L, false));
    }

    @Test
    void p4_kbNotExist_skippedNoCrash() {
        when(knowledgeBaseService.ensure(999L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "KB 不存在"));

        assertTrue(resolver.resolveEffectiveKbs("CHAT", List.of(999L), null, null, 7L, false).isEmpty());
    }

    @Test
    void chat_emptyScope_returnsEmpty() {
        assertTrue(resolver.resolveEffectiveKbs("CHAT", List.of(), null, null, 7L, false).isEmpty());
        assertTrue(resolver.resolveEffectiveKbs("CHAT", null, null, null, 7L, false).isEmpty());
    }

    @Test
    void agentMode_resolvesFromAgentBinding() {
        when(agentKbBindingService.listKbIds(5L)).thenReturn(List.of(1L));
        KnowledgeBase kb1 = kb("m");
        when(knowledgeBaseService.ensure(1L)).thenReturn(kb1);
        when(knowledgeBaseService.canRead(same(kb1), eq(7L), anyBoolean())).thenReturn(true);

        assertEquals(List.of(1L), resolver.resolveEffectiveKbs("AGENT", null, 5L, null, 7L, false));
        verify(agentKbBindingService).listKbIds(5L);
    }

    @Test
    void workflowMode_resolvesFromWorkflowBinding() {
        when(workflowKbBindingService.listKbIds(8L)).thenReturn(List.of(2L));
        KnowledgeBase kb2 = kb("m");
        when(knowledgeBaseService.ensure(2L)).thenReturn(kb2);
        when(knowledgeBaseService.canRead(same(kb2), eq(7L), anyBoolean())).thenReturn(true);

        assertEquals(List.of(2L), resolver.resolveEffectiveKbs("WORKFLOW", null, null, 8L, 7L, false));
    }

    @Test
    void sameModel_mixedBinding_restrictedToFirstGroup() {
        KnowledgeBase kbA = kb("model-A");
        KnowledgeBase kbB = kb("model-B");
        when(knowledgeBaseService.ensure(1L)).thenReturn(kbA);
        when(knowledgeBaseService.ensure(2L)).thenReturn(kbB);
        when(knowledgeBaseService.canRead(same(kbA), eq(7L), anyBoolean())).thenReturn(true);
        when(knowledgeBaseService.canRead(same(kbB), eq(7L), anyBoolean())).thenReturn(true);

        // 混合模型 → 限定首个模型组（kbId 升序 = 1 的 model-A），kb2 剔除
        assertEquals(List.of(1L), resolver.resolveEffectiveKbs("CHAT", List.of(1L, 2L), null, null, 7L, false));
    }

    @Test
    void resolveNodeKbs_canReadFiltered() {
        KnowledgeBase kb1 = kb("m");
        when(knowledgeBaseService.ensure(1L)).thenReturn(kb1);
        when(knowledgeBaseService.canRead(same(kb1), eq(7L), eq(false))).thenReturn(true);

        assertEquals(List.of(1L), resolver.resolveNodeKbs(List.of(1L), 7L));
    }

    @Test
    void resolveNodeKbs_emptyConfig_returnsEmpty() {
        assertTrue(resolver.resolveNodeKbs(null, 7L).isEmpty());
        assertTrue(resolver.resolveNodeKbs(List.of(), 7L).isEmpty());
    }

    private KnowledgeBase kb(String model) {
        KnowledgeBase k = new KnowledgeBase();
        k.setEmbeddingModel(model);
        return k;
    }
}
