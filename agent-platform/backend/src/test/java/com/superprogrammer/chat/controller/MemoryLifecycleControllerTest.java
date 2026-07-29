package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryLifecycleActionVO;
import com.superprogrammer.chat.dto.MemoryLifecycleProjectVO;
import com.superprogrammer.chat.dto.MemoryLifecyclePullRequest;
import com.superprogrammer.chat.service.internal.MemoryLifecycleService;
import com.superprogrammer.common.exception.BusinessException;
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
 * 计划12 · F-4b 前置 · MemoryLifecycleController 单测（Mockito，mock service + stub SecurityContext）。
 * <p>
 * 覆盖：4 端点登录门槛（401）+ 正常透传 + projectName 可空透传。
 */
@ExtendWith(MockitoExtension.class)
class MemoryLifecycleControllerTest {

    @Mock MemoryLifecycleService lifecycleService;

    private MemoryLifecycleController controller;

    @BeforeEach
    void setUp() {
        controller = new MemoryLifecycleController(lifecycleService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listDeparted_returnsList() {
        MemoryLifecycleProjectVO row = MemoryLifecycleProjectVO.builder()
                .projectId(100L).projectName("P").turnCount(2).build();
        when(lifecycleService.listDepartedProjects(1L)).thenReturn(List.of(row));

        List<MemoryLifecycleProjectVO> out = controller.listDepartedProjects().getBody().getData();

        assertEquals(1, out.size());
        assertEquals(100L, out.get(0).getProjectId());
    }

    @Test
    void listDeleted_returnsList() {
        when(lifecycleService.listDeletedProjects(1L)).thenReturn(List.of());
        assertTrue(controller.listDeletedProjects().getBody().getData().isEmpty());
    }

    @Test
    void copyTo_passesProjectName() {
        MemoryLifecycleActionVO vo = MemoryLifecycleActionVO.builder()
                .newProjectId(200L).affectedTurns(3).build();
        when(lifecycleService.copyDepartedProjectTo(1L, 100L, "新")).thenReturn(vo);
        MemoryLifecyclePullRequest req = new MemoryLifecyclePullRequest();
        req.setProjectName("新");

        MemoryLifecycleActionVO out = controller.copyDepartedProjectTo(100L, req).getBody().getData();

        assertEquals(200L, out.getNewProjectId());
        verify(lifecycleService).copyDepartedProjectTo(1L, 100L, "新");
    }

    @Test
    void copyTo_nullBody_passesNullName() {
        when(lifecycleService.copyDepartedProjectTo(1L, 100L, null))
                .thenReturn(MemoryLifecycleActionVO.builder().build());
        controller.copyDepartedProjectTo(100L, null);
        verify(lifecycleService).copyDepartedProjectTo(1L, 100L, null);
    }

    @Test
    void restore_passesThrough() {
        when(lifecycleService.restoreDeletedProject(1L, 100L, null))
                .thenReturn(MemoryLifecycleActionVO.builder().affectedTurns(2).build());
        MemoryLifecycleActionVO out = controller.restoreDeletedProject(100L, null).getBody().getData();
        assertEquals(2, out.getAffectedTurns());
    }

    @Test
    void notLogin_unauthorized() {
        SecurityContextHolder.clearContext();
        BusinessException ex = assertThrows(BusinessException.class, () -> controller.listDepartedProjects());
        assertEquals(401, ex.getCode());
        assertThrows(BusinessException.class, () -> controller.copyDepartedProjectTo(100L, null));
        assertThrows(BusinessException.class, () -> controller.listDeletedProjects());
        assertThrows(BusinessException.class, () -> controller.restoreDeletedProject(100L, null));
        verifyNoInteractions(lifecycleService);
    }
}
