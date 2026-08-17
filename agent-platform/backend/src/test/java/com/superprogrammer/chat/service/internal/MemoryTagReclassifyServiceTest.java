package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 5x 四轮 C8：标签「重新归类」（U7）。
 * AC：命中增补 / 只增不删 / 范围过滤+上限硬卡 / 非本人 404 / LLM 失败保守跳批。
 */
@ExtendWith(MockitoExtension.class)
class MemoryTagReclassifyServiceTest {

    @Mock private MemoryTagMapper tagMapper;
    @Mock private MemoryTurnMapper turnMapper;
    @Mock private LlmGateway llmGateway;
    @Mock private SystemSettingService systemSettingService;

    private MemoryTagReclassifyService service;

    @BeforeEach
    void setUp() {
        service = new MemoryTagReclassifyService(tagMapper, turnMapper, llmGateway,
                new ObjectMapper(), systemSettingService);
    }

    private MemoryTag tag(Long ownerId) {
        MemoryTag t = new MemoryTag();
        t.setId(9L);
        t.setUserId(ownerId);
        t.setSubject("我");
        t.setTopic("技术学习");
        t.setLabel("Java");
        t.setUsageCount(3);
        t.setCreatedAt(OffsetDateTime.parse("2026-08-01T00:00:00+08:00"));
        return t;
    }

    private MemoryTurn turn(Long id, String l1) {
        MemoryTurn t = new MemoryTurn();
        t.setId(id);
        t.setUserId(100L);
        t.setL1Summary(l1);
        return t;
    }

    private MemoryTagReclassifyService.MemoryTagReclassifyParams params(
            Boolean olderThanTag, String start, String end, Integer limit) {
        return new MemoryTagReclassifyService.MemoryTagReclassifyParams(olderThanTag, start, end, limit, null);
    }

    @Test
    @DisplayName("C8 命中增补：LLM 命中行 appendTagId + incrementUsage；未命中行不动")
    void reclassify_hitAppendsTagAndBumpsUsage() {
        when(tagMapper.selectById(9L)).thenReturn(tag(100L));
        when(turnMapper.findReclassifyCandidates(eq(100L), eq(9L), any(), any(), any(), anyInt()))
                .thenReturn(List.of(turn(1L, "聊了 Java 虚拟机"), turn(2L, "聊了旅行计划")));
        when(systemSettingService.getMemoryJudgeModel()).thenReturn("judge-model");
        when(llmGateway.chat(any(), eq(100L))).thenReturn(
                LlmResponse.builder().content("[1]").build());
        when(turnMapper.appendTagId(1L, 9L)).thenReturn(1);

        var report = service.reclassify(9L, 100L, params(true, null, null, null));

        assertEquals(2, report.scanned);
        assertEquals(2, report.judged);
        assertEquals(1, report.hits);
        verify(turnMapper).appendTagId(1L, 9L);
        verify(turnMapper, never()).appendTagId(2L, 9L);
        verify(tagMapper).incrementUsage(9L);
    }

    @Test
    @DisplayName("C8 只增不删：命中仅走 appendTagId 原子增补，无任何替换/删除路径（拍板⑤）")
    void reclassify_neverRemovesOldTags() {
        when(tagMapper.selectById(9L)).thenReturn(tag(100L));
        when(turnMapper.findReclassifyCandidates(eq(100L), eq(9L), any(), any(), any(), anyInt()))
                .thenReturn(List.of(turn(1L, "聊了 Java"), turn(2L, "聊了 Spring")));
        when(systemSettingService.getMemoryJudgeModel()).thenReturn("judge-model");
        when(llmGateway.chat(any(), eq(100L))).thenReturn(
                LlmResponse.builder().content("[1,2]").build());
        when(turnMapper.appendTagId(anyLong(), anyLong())).thenReturn(1);

        var report = service.reclassify(9L, 100L, params(true, null, null, null));

        assertEquals(2, report.hits);
        // turn 侧只允许两种交互：候选查询 + 增补——applyBackfill/softDeleteByIds/updateById 全禁止
        verify(turnMapper, times(2)).appendTagId(anyLong(), eq(9L));
        verifyNoMoreInteractions(turnMapper);
    }

    @Test
    @DisplayName("C8 范围过滤：olderThanTag 缺省=标签创建前；limit 超上限压到 200 硬卡")
    void reclassify_rangeFiltersAndLimitClamp() {
        MemoryTag t = tag(100L);
        when(tagMapper.selectById(9L)).thenReturn(t);
        when(turnMapper.findReclassifyCandidates(anyLong(), anyLong(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.reclassify(9L, 100L, params(null, "2026-07-01T00:00:00+08:00", "2026-07-31T23:59:59+08:00", 9999));

        // olderThanTag=null → 缺省 true → olderThan=tag.createdAt；limit 9999 → 压 200
        verify(turnMapper).findReclassifyCandidates(eq(100L), eq(9L),
                eq(OffsetDateTime.parse("2026-08-01T00:00:00+08:00")),
                eq(OffsetDateTime.parse("2026-07-01T00:00:00+08:00")),
                eq(OffsetDateTime.parse("2026-07-31T23:59:59+08:00")),
                eq(200));
        verifyNoInteractions(llmGateway);

        // olderThanTag=false → olderThan 传 null（不限定建标签前）
        service.reclassify(9L, 100L, params(false, null, null, null));
        verify(turnMapper).findReclassifyCandidates(eq(100L), eq(9L), isNull(), isNull(), isNull(), eq(200));
    }

    @Test
    @DisplayName("C8 非本人/不存在 → NOT_FOUND 统一话术，不泄露存在性、不动任何数据")
    void reclassify_notOwner_notFound() {
        when(tagMapper.selectById(9L)).thenReturn(tag(200L));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reclassify(9L, 100L, params(true, null, null, null)));
        assertEquals(com.superprogrammer.common.exception.ErrorCode.NOT_FOUND.getCode(), ex.getCode(), "非本人统一 404");

        when(tagMapper.selectById(404L)).thenReturn(null);
        assertThrows(BusinessException.class,
                () -> service.reclassify(404L, 100L, params(true, null, null, null)));
        verifyNoInteractions(turnMapper, llmGateway);
    }

    @Test
    @DisplayName("C8 LLM 判定失败/不可解析 → 该批保守跳过（不误挂），报告 failBatches=1")
    void reclassify_llmFail_skipsBatch() {
        when(tagMapper.selectById(9L)).thenReturn(tag(100L));
        when(turnMapper.findReclassifyCandidates(eq(100L), eq(9L), any(), any(), any(), anyInt()))
                .thenReturn(List.of(turn(1L, "聊了 Java")));
        when(systemSettingService.getMemoryJudgeModel()).thenReturn("judge-model");
        when(llmGateway.chat(any(), eq(100L))).thenThrow(new RuntimeException("LLM 超时"));

        var report = service.reclassify(9L, 100L, params(true, null, null, null));

        assertEquals(1, report.scanned);
        assertEquals(1, report.judged);
        assertEquals(0, report.hits);
        assertEquals(1, report.llmFailBatches);
        verify(turnMapper, never()).appendTagId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("C8 无可判文本（l1/raw 皆空）→ 跳过不调 LLM；候选空 → 零成本短路")
    void reclassify_noTextRows_skipsLlm() {
        when(tagMapper.selectById(9L)).thenReturn(tag(100L));
        when(turnMapper.findReclassifyCandidates(eq(100L), eq(9L), any(), any(), any(), anyInt()))
                .thenReturn(List.of(turn(1L, null), turn(2L, "  ")));

        var report = service.reclassify(9L, 100L, params(true, null, null, null));

        assertEquals(2, report.scanned);
        assertEquals(0, report.judged);
        verifyNoInteractions(llmGateway);
    }

    @Test
    @DisplayName("C8 dryRun 预估：只计数不调 LLM 不落库（前端 modal「预估条数」）")
    void reclassify_dryRun_countsOnly() {
        when(tagMapper.selectById(9L)).thenReturn(tag(100L));
        when(turnMapper.findReclassifyCandidates(eq(100L), eq(9L), any(), any(), any(), anyInt()))
                .thenReturn(List.of(turn(1L, "聊了 Java"), turn(2L, "聊了旅行")));

        var report = service.reclassify(9L, 100L, new MemoryTagReclassifyService.MemoryTagReclassifyParams(
                true, null, null, null, true));

        assertEquals(2, report.scanned);
        assertEquals(0, report.judged);
        assertEquals(0, report.hits);
        verifyNoInteractions(llmGateway);
        verify(turnMapper, never()).appendTagId(anyLong(), anyLong());
    }
}
