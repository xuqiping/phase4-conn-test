package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.chat.service.internal.MemoryGenerator.GenResult;
import com.superprogrammer.chat.service.internal.MemoryGenerator.SideLayers;
import com.superprogrammer.chat.service.internal.MemoryPrefilter.FilterResult;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划12 · E-2 · MemoryBackfillService 单测（Mockito）。
 * 验：单侧喂入 / 空召回退出 / prefilter 双跳 / LLM 失败 / 无事实 / 归一 null / 分批。
 */
@ExtendWith(MockitoExtension.class)
class MemoryBackfillServiceTest {

    @Mock MemoryPrefilter prefilter;
    @Mock MemoryGenerator generator;
    @Mock MemoryTagResolver tagResolver;
    @Mock MemoryTurnMapper turnMapper;
    @Mock SystemSettingService systemSettingService;

    @InjectMocks MemoryBackfillService service;

    @BeforeEach
    void setUp() {
        lenient().when(systemSettingService.getMemoryJudgeModel()).thenReturn("doubao-seed-2.0-code");
    }

    private static MemoryTurn raw(Long id, String direction) {
        MemoryTurn t = new MemoryTurn();
        t.setId(id);
        t.setDirection(direction);
        t.setRawContent("用户用Java写后端");
        t.setGenDone(false);
        return t;
    }

    private static SideLayers side(String topic, String label) {
        return new SideLayers("我", topic, label, "l1概要", "l2详述", null);
    }

    // ---- 1. 空召回 → 0 处理，不调生成 ----

    @Test
    void emptyScopeReturnsZeroWithoutLlm() {
        when(turnMapper.findRawTurnsForBackfill(anyLong(), anyInt()))
                .thenReturn(List.of());

        int n = service.backfillScope(1L);

        org.junit.jupiter.api.Assertions.assertEquals(0, n);
        verify(prefilter, never()).filter(any(), any());
        verify(generator, never()).generate(anyLong(), any(), any(), any(), any());
    }

    // ---- 2. INPUT raw 命中 → 单侧生成 + 归一 + applyBackfill 带 tag+l1+l2 ----

    @Test
    void inputRawBackfillsWithTagAndLayers() {
        when(turnMapper.findRawTurnsForBackfill(anyLong(), anyInt()))
                .thenReturn(List.of(raw(10L, "INPUT")))
                .thenReturn(List.of());
        when(prefilter.filter(eq("用户用Java写后端"), eq(null)))
                .thenReturn(new FilterResult(false, true, null, "空回复"));
        when(generator.generate(eq(1L), eq("用户用Java写后端"), eq(null), any(), any()))
                .thenReturn(new GenResult(side("工作", "职业"), null));
        when(tagResolver.resolve(1L, "我", "工作", "职业")).thenReturn(77L);

        int n = service.backfillScope(1L);

        org.junit.jupiter.api.Assertions.assertEquals(1, n);
        verify(turnMapper).applyBackfill(eq(10L), eq(List.of(77L)), eq("l1概要"), eq("l2详述"), eq(1L));
    }

    // ---- 3. OUTPUT raw → 取 gen.output 侧 ----

    @Test
    void outputRawUsesOutputSide() {
        when(turnMapper.findRawTurnsForBackfill(anyLong(), anyInt()))
                .thenReturn(List.of(raw(20L, "OUTPUT")))
                .thenReturn(List.of());
        when(prefilter.filter(eq(null), eq("用户用Java写后端")))
                .thenReturn(new FilterResult(true, false, "空回复", null));
        when(generator.generate(eq(1L), eq(null), eq("用户用Java写后端"), any(), any()))
                .thenReturn(new GenResult(null, side("偏好", "编程语言")));
        when(tagResolver.resolve(1L, "我", "偏好", "编程语言")).thenReturn(88L);

        service.backfillScope(1L);

        verify(turnMapper).applyBackfill(eq(20L), eq(List.of(88L)), anyString(), anyString(), eq(1L));
    }

    // ---- 4. prefilter 双跳 → 空 tag 置 gen_done=true（不再进 backfill）----

    @Test
    void bothSidesSkippedMarksProcessedEmptyTag() {
        when(turnMapper.findRawTurnsForBackfill(anyLong(), anyInt()))
                .thenReturn(List.of(raw(30L, "INPUT")))
                .thenReturn(List.of());
        when(prefilter.filter(any(), any())).thenReturn(new FilterResult(true, true, "过短", "空回复"));

        service.backfillScope(1L);

        verify(generator, never()).generate(anyLong(), any(), any(), any(), any());
        verify(turnMapper).applyBackfill(eq(30L), eq(List.of()), eq(null), eq(null), eq(1L));
    }

    // ---- 5. LLM 失败（gen=null）→ 空 tag 置 gen_done=true ----

    @Test
    void llmFailureMarksProcessedEmptyTag() {
        when(turnMapper.findRawTurnsForBackfill(anyLong(), anyInt()))
                .thenReturn(List.of(raw(40L, "INPUT")))
                .thenReturn(List.of());
        when(prefilter.filter(any(), any())).thenReturn(new FilterResult(false, true, null, "空回复"));
        when(generator.generate(anyLong(), any(), any(), any(), any())).thenReturn(null);

        service.backfillScope(1L);

        verify(turnMapper).applyBackfill(eq(40L), eq(List.of()), eq(null), eq(null), eq(1L));
    }

    // ---- 6. side 无核心（缺 topic）→ 空 tag ----

    @Test
    void sideMissingCoreMarksEmptyTag() {
        when(turnMapper.findRawTurnsForBackfill(anyLong(), anyInt()))
                .thenReturn(List.of(raw(50L, "INPUT")))
                .thenReturn(List.of());
        when(prefilter.filter(any(), any())).thenReturn(new FilterResult(false, true, null, "空回复"));
        // topic 空 → hasCore=false
        when(generator.generate(anyLong(), any(), any(), any(), any()))
                .thenReturn(new GenResult(side("", "职业"), null));

        service.backfillScope(1L);

        verify(tagResolver, never()).resolve(anyLong(), any(), any(), any());
        verify(turnMapper).applyBackfill(eq(50L), eq(List.of()), eq(null), eq(null), eq(1L));
    }

    // ---- 7. tagResolver 返 null → 空 tag 但仍 gen_done=true（生成成功，归一 miss）----

    @Test
    void resolverNullStillMarksGenDone() {
        when(turnMapper.findRawTurnsForBackfill(anyLong(), anyInt()))
                .thenReturn(List.of(raw(60L, "INPUT")))
                .thenReturn(List.of());
        when(prefilter.filter(any(), any())).thenReturn(new FilterResult(false, true, null, "空回复"));
        when(generator.generate(anyLong(), any(), any(), any(), any()))
                .thenReturn(new GenResult(side("工作", "职业"), null));
        when(tagResolver.resolve(anyLong(), any(), any(), any())).thenReturn(null);

        service.backfillScope(1L);

        verify(turnMapper).applyBackfill(eq(60L), eq(List.of()), anyString(), anyString(), eq(1L));
    }

    // ---- 8. 分批：25 raw → 两批（20+5），全处理 ----

    @Test
    void batchesOverTwentyAllProcessed() {
        java.util.List<MemoryTurn> batch1 = new java.util.ArrayList<>();
        for (long i = 1; i <= 20; i++) batch1.add(raw(i, "INPUT"));
        java.util.List<MemoryTurn> batch2 = new java.util.ArrayList<>();
        for (long i = 21; i <= 25; i++) batch2.add(raw(i, "INPUT"));

        when(turnMapper.findRawTurnsForBackfill(anyLong(), anyInt()))
                .thenReturn(batch1)
                .thenReturn(batch2)
                .thenReturn(List.of());
        when(prefilter.filter(any(), any())).thenReturn(new FilterResult(true, true, "过短", "空回复"));

        int n = service.backfillScope(1L);

        org.junit.jupiter.api.Assertions.assertEquals(25, n);
        verify(turnMapper, times(25)).applyBackfill(anyLong(), eq(List.of()), eq(null), eq(null), eq(1L));
        verify(turnMapper, times(3)).findRawTurnsForBackfill(anyLong(), anyInt());
    }
}
