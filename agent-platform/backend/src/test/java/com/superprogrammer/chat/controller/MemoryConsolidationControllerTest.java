package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.controller.MemoryConsolidationController.ScopeAutoSaveRequest;
import com.superprogrammer.chat.controller.MemoryConsolidationController.ScopeAutoView;
import com.superprogrammer.chat.dto.MemoryConsolidationTargetView;
import com.superprogrammer.chat.dto.MemoryConsolidationTriggerRequest;
import com.superprogrammer.chat.dto.MemorySummaryConflictResolveRequest;
import com.superprogrammer.chat.entity.MemoryConflict;
import com.superprogrammer.chat.mapper.MemoryConsolidationScopeMapper;
import com.superprogrammer.chat.service.internal.MemoryConsolidationService;
import com.superprogrammer.chat.service.internal.MemoryConsolidationService.SummarizeResult;
import com.superprogrammer.chat.service.internal.MemoryConflictResolutionService;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划12 · E-7 · MemoryConsolidationController 单测（委派 + 鉴权）。
 */
@ExtendWith(MockitoExtension.class)
class MemoryConsolidationControllerTest {

    @Mock MemoryConsolidationService consolidationService;
    @Mock MemoryConflictResolutionService conflictService;
    @Mock MemoryConsolidationScopeMapper scopeMapper;

    @InjectMocks MemoryConsolidationController controller;

    @BeforeEach
    void loginAsUid1() {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(1L, "jwt", java.util.List.of()));
        SecurityContextHolder.setContext(ctx);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void targetsDelegatesToListTargets() {
        when(consolidationService.listTargets(eq(1L))).thenReturn(List.of(
                MemoryConsolidationTargetView.builder().scopeKind("PERSONAL").displayName("个人").build()));

        var resp = controller.targets();

        assertNotNull(resp.getBody());
        verify(consolidationService).listTargets(eq(1L));
    }

    @Test
    void triggerDelegatesToTriggerManual() {
        MemoryConsolidationTriggerRequest req = new MemoryConsolidationTriggerRequest();
        when(consolidationService.triggerManual(eq(1L), eq(req))).thenReturn(new SummarizeResult());

        controller.trigger(req);

        verify(consolidationService).triggerManual(eq(1L), eq(req));
    }

    @Test
    void resolveDelegatesToConflictService() {
        MemorySummaryConflictResolveRequest req = new MemorySummaryConflictResolveRequest();
        req.setDecision("KEEP_BOTH");
        when(conflictService.resolve(eq(1L), eq(77L), eq("KEEP_BOTH"))).thenReturn(true);

        var resp = controller.resolve(77L, req);

        assertEquals(Boolean.TRUE, resp.getBody().getData());
        verify(conflictService).resolve(eq(1L), eq(77L), eq("KEEP_BOTH"));
    }

    @Test
    void pendingDelegatesAndMapsToVO() {
        MemoryConflict c = new MemoryConflict();
        c.setId(77L); c.setStatus("PENDING"); c.setAskText("冲突?");
        c.setCreatedAt(java.time.OffsetDateTime.now());
        when(conflictService.listPending(eq(1L))).thenReturn(List.of(c));

        var resp = controller.pendingConflicts();

        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().getData().size());
        assertEquals(77L, resp.getBody().getData().get(0).getConflictId());
    }

    @Test
    void pendingCountDelegates() {
        when(conflictService.countPending(eq(1L))).thenReturn(3);

        var resp = controller.pendingCount();

        assertEquals(3, resp.getBody().getData());
    }

    @Test
    void saveAutoScopesUpsertsEach() {
        ScopeAutoSaveRequest req = new ScopeAutoSaveRequest();
        req.setScopes(List.of(new ScopeAutoView("PERSONAL", null, true),
                new ScopeAutoView("PROJECT", 99L, false)));

        controller.saveAutoScopes(req);

        verify(scopeMapper).upsertScope(eq(1L), eq("PERSONAL"), eq(null), eq(true), any());
        verify(scopeMapper).upsertScope(eq(1L), eq("PROJECT"), eq(99L), eq(false), any());
    }

    @Test
    void unauthorizedWhenNoAuth() {
        SecurityContextHolder.clearContext();
        assertThrows(BusinessException.class, () -> controller.targets());
        verify(consolidationService, org.mockito.Mockito.never()).listTargets(anyLong());
    }
}
