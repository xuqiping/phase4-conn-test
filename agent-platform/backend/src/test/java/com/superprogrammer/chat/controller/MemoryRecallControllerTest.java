package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryRecallPreviewRequest;
import com.superprogrammer.chat.dto.MemoryRecallResult;
import com.superprogrammer.chat.dto.MemoryRecallScopeRequest;
import com.superprogrammer.chat.dto.MemoryRecallScopeView;
import com.superprogrammer.chat.service.internal.MemoryProjectUserGrantService;
import com.superprogrammer.chat.service.internal.MemoryRecallPipeline;
import com.superprogrammer.chat.service.internal.MemoryRecallScopePreferenceService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.project.mapper.ProjectMapper;
import com.superprogrammer.project.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · D-7 · MemoryRecallController 单测（Mockito，mock pipeline + prefService + stub SecurityContext）。
 * <p>
 * 覆盖：preview query 校验 / scope 透传 / getScope 默认与回显 / saveScope / 未登录拦截。
 */
@ExtendWith(MockitoExtension.class)
class MemoryRecallControllerTest {

    @Mock
    MemoryRecallPipeline pipeline;
    @Mock
    MemoryRecallScopePreferenceService prefService;
    @Mock
    ProjectService projectService;
    @Mock
    ProjectMapper projectMapper;
    @Mock
    MemoryProjectUserGrantService grantService;

    private MemoryRecallController controller;

    @BeforeEach
    void setUp() {
        controller = new MemoryRecallController(pipeline, prefService, projectService, projectMapper, grantService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ===== preview 校验 =====

    @Test
    void preview_nullReq_throwsBadRequest() {
        BusinessException ex = assertThrows(BusinessException.class, () -> controller.preview(null));
        assertTrue(ex.getMessage().contains("query"));
    }

    @Test
    void preview_blankQuery_throwsBadRequest() {
        MemoryRecallPreviewRequest req = new MemoryRecallPreviewRequest();
        req.setQuery("   ");
        assertThrows(BusinessException.class, () -> controller.preview(req));
        verifyNoInteractions(pipeline);
    }

    @Test
    void preview_valid_returnsResult() {
        MemoryRecallPreviewRequest req = new MemoryRecallPreviewRequest();
        req.setQuery("爱好");
        MemoryRecallResult stub = MemoryRecallResult.builder().assembledText("记忆").summaryCount(1).build();
        when(pipeline.recall(eq("爱好"), any(), eq(1L), any())).thenReturn(stub);

        MemoryRecallResult data = controller.preview(req).getBody().getData();

        assertSame(stub, data);
        verify(pipeline).recall(eq("爱好"), any(), eq(1L), any());
    }

    @Test
    void preview_scopeNull_passedThrough() {
        MemoryRecallPreviewRequest req = new MemoryRecallPreviewRequest();
        req.setQuery("q");
        when(pipeline.recall(anyString(), any(), anyLong(), any())).thenReturn(MemoryRecallResult.builder().build());

        controller.preview(req);

        verify(pipeline).recall(eq("q"), isNull(), eq(1L), any());
    }

    // ===== scope 持久化 =====

    @Test
    void getScope_noHistory_returnsDefaultPersonal() {
        when(prefService.getScope(1L)).thenReturn(null);
        MemoryRecallScopeView data = controller.getScope().getBody().getData();
        assertEquals(true, data.isPersonalOn(), "无历史默认 {个人}");
    }

    @Test
    void getScope_history_returnsSaved() {
        MemoryRecallScopeRequest saved = new MemoryRecallScopeRequest();
        saved.setPersonalOn(false);
        saved.setProjectIds(List.of(7L));
        when(prefService.getScope(1L)).thenReturn(saved);

        MemoryRecallScopeView data = controller.getScope().getBody().getData();

        assertEquals(false, data.isPersonalOn());
        assertEquals(List.of(7L), data.getProjectIds());
    }

    // ===== 记忆二期 P1：availableProjects = accessible ∪ granted =====

    @Test
    void getScope_buildsAvailableProjects_accessibleUnionGranted() {
        when(prefService.getScope(1L)).thenReturn(null);
        when(projectService.listAccessibleProjectIds(1L)).thenReturn(java.util.Set.of(10L));
        when(grantService.findActiveGrantedProjectIds(1L)).thenReturn(List.of(30L));  // 非成员，仅授权
        com.superprogrammer.project.entity.Project p10 = new com.superprogrammer.project.entity.Project();
        p10.setId(10L);
        p10.setName("成员项目");
        com.superprogrammer.project.entity.Project p30 = new com.superprogrammer.project.entity.Project();
        p30.setId(30L);
        p30.setName("授权项目");
        when(projectMapper.selectBatchIds(java.util.List.of(10L, 30L))).thenReturn(java.util.List.of(p10, p30));

        MemoryRecallScopeView data = controller.getScope().getBody().getData();

        assertEquals(2, data.getAvailableProjects().size());
        var viaGrantFlags = data.getAvailableProjects().stream()
                .collect(java.util.stream.Collectors.toMap(
                        MemoryRecallScopeView.ProjectOption::getProjectId,
                        MemoryRecallScopeView.ProjectOption::isViaGrant));
        assertEquals(false, viaGrantFlags.get(10L), "成员项目 viaGrant=false");
        assertEquals(true, viaGrantFlags.get(30L), "个人授权项目 viaGrant=true");
    }

    @Test
    void saveScope_callsPrefService() {
        MemoryRecallScopeRequest req = new MemoryRecallScopeRequest();
        req.setPersonalOn(false);
        controller.saveScope(req);
        verify(prefService).saveScope(1L, req);
    }

    // ===== 未登录 =====

    @Test
    void notLogin_throwsUnauthorized() {
        SecurityContextHolder.clearContext();
        assertThrows(BusinessException.class, () -> controller.getScope());
        assertThrows(BusinessException.class, () -> controller.saveScope(null));
        MemoryRecallPreviewRequest req = new MemoryRecallPreviewRequest();
        req.setQuery("q");
        assertThrows(BusinessException.class, () -> controller.preview(req));
    }
}
