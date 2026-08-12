package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.entity.MemoryProjectEntry;
import com.superprogrammer.chat.entity.MemoryProjectRule;
import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆二期 P1 · 收录审核 service 单测（FR-005）。
 * 核心：状态机（PENDING→ACTIVE / PENDING→软删+负例）、权边界（owner/admin 审、作者撤回）、读权分层。
 */
@ExtendWith(MockitoExtension.class)
class MemoryEntryReviewServiceTest {

    @Mock private MemoryProjectEntryMapper entryMapper;
    @Mock private MemoryProjectRuleService ruleService;

    private MemoryEntryReviewService service;

    @BeforeEach
    void setUp() {
        service = new MemoryEntryReviewService(entryMapper, ruleService);
    }

    private MemoryProjectEntry pendingEntry() {
        MemoryProjectEntry e = new MemoryProjectEntry();
        e.setId(5L);
        e.setProjectId(1L);
        e.setAuthorUserId(100L);
        e.setL1Summary("聊了 SeedDance cfg");
        e.setStatus(MemoryProjectEntry.STATUS_PENDING_REVIEW);
        e.setConfidence(0.6);
        return e;
    }

    // AC-FR-005：收 → ACTIVE + reviewed_by 留痕
    @Test
    void review_approve_activates() {
        when(entryMapper.selectById(5L)).thenReturn(pendingEntry());
        when(ruleService.isOwnerOrAdmin(1L, 200L)).thenReturn(true);

        service.review(5L, MemoryEntryReviewService.ACTION_APPROVE, 200L);

        ArgumentCaptor<MemoryProjectEntry> captor = ArgumentCaptor.forClass(MemoryProjectEntry.class);
        verify(entryMapper).updateById(captor.capture());
        assertEquals(MemoryProjectEntry.STATUS_ACTIVE, captor.getValue().getStatus());
        assertEquals(200L, captor.getValue().getReviewedBy());
    }

    // AC-FR-005：弃 → 软删 + 负例反哺（条目 L1 进规则负例）
    @Test
    void review_reject_softDeletesAndFeedsNegative() {
        when(entryMapper.selectById(5L)).thenReturn(pendingEntry());
        when(ruleService.isOwnerOrAdmin(1L, 200L)).thenReturn(true);

        service.review(5L, MemoryEntryReviewService.ACTION_REJECT, 200L);

        verify(entryMapper).deleteById(5L);
        verify(ruleService).appendNegativeExample(1L, "聊了 SeedDance cfg");
    }

    // 越权审核（非 owner/admin）→ 403，不落任何写
    @Test
    void review_notOwner_forbidden() {
        when(entryMapper.selectById(5L)).thenReturn(pendingEntry());
        when(ruleService.isOwnerOrAdmin(1L, 300L)).thenReturn(false);

        assertThrows(BusinessException.class,
                () -> service.review(5L, MemoryEntryReviewService.ACTION_APPROVE, 300L));
        verify(entryMapper, never()).updateById(any(MemoryProjectEntry.class));
        verify(entryMapper, never()).deleteById(any(Long.class));
    }

    // 非 PENDING_REVIEW（已 ACTIVE）→ 400 幂等拒绝
    @Test
    void review_notPending_rejected() {
        MemoryProjectEntry e = pendingEntry();
        e.setStatus(MemoryProjectEntry.STATUS_ACTIVE);
        when(entryMapper.selectById(5L)).thenReturn(e);

        assertThrows(BusinessException.class,
                () -> service.review(5L, MemoryEntryReviewService.ACTION_APPROVE, 200L));
    }

    // 非法 action → 400
    @Test
    void review_badAction_rejected() {
        when(entryMapper.selectById(5L)).thenReturn(pendingEntry());
        when(ruleService.isOwnerOrAdmin(1L, 200L)).thenReturn(true);
        assertThrows(BusinessException.class, () -> service.review(5L, "bogus", 200L));
    }

    // AC-FR-005：作者撤回自己条目；非作者 → 403
    @Test
    void withdraw_authorOnly() {
        when(entryMapper.selectById(5L)).thenReturn(pendingEntry());
        service.withdraw(5L, 100L);
        verify(entryMapper).deleteById(5L);

        when(entryMapper.selectById(6L)).thenReturn(pendingEntry());
        assertThrows(BusinessException.class, () -> service.withdraw(6L, 999L));
    }

    // 读权分层：owner 全量（authorUserId=null）；成员收窄到自己；VO 附命中规则文案
    @Test
    void listEntries_visibilityScoping() {
        MemoryProjectEntryVO vo = MemoryProjectEntryVO.builder().id(5L).build();
        MemoryProjectRule rule = new MemoryProjectRule();
        rule.setRuleText("涉及 SeedDance");

        when(ruleService.isOwnerOrAdmin(1L, 200L)).thenReturn(true);
        when(entryMapper.listByProject(eq(1L), isNull(), isNull())).thenReturn(List.of(vo));
        when(ruleService.findActiveRule(1L)).thenReturn(rule);

        List<MemoryProjectEntryVO> ownerList = service.listEntries(1L, null, 200L);
        assertEquals("涉及 SeedDance", ownerList.get(0).getRuleText());

        when(ruleService.isOwnerOrAdmin(1L, 100L)).thenReturn(false);
        when(entryMapper.listByProject(eq(1L), eq("PENDING_REVIEW"), eq(100L))).thenReturn(List.of(vo));
        List<MemoryProjectEntryVO> memberList = service.listEntries(1L, "PENDING_REVIEW", 100L);
        assertEquals(1, memberList.size());
        verify(entryMapper).listByProject(1L, "PENDING_REVIEW", 100L);
    }

    // 无规则时 ruleText=null 不炸
    @Test
    void listEntries_noRule_nullRuleText() {
        when(ruleService.isOwnerOrAdmin(1L, 200L)).thenReturn(true);
        when(entryMapper.listByProject(eq(1L), isNull(), isNull()))
                .thenReturn(List.of(MemoryProjectEntryVO.builder().id(5L).build()));
        when(ruleService.findActiveRule(1L)).thenReturn(null);

        List<MemoryProjectEntryVO> list = service.listEntries(1L, null, 200L);
        assertNull(list.get(0).getRuleText());
    }
}
