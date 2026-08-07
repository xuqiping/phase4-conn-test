package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.chat.service.internal.MemoryGenerator.SideLayers;
import com.superprogrammer.chat.service.internal.MemoryPrefilter.FilterResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划12 · C · 写入链编排单测（Mockito，全依赖 mock）。
 * 出口对齐 plan C：两侧均跳不写 / gen-off 写 raw / gen-on 写生成层 / born_personal 矩阵 / 卸空转 / 非项目恒个人 / 异步包装 / 缓存 evict。
 */
@ExtendWith(MockitoExtension.class)
class MemoryGenerationServiceTest {

    @Mock private MemoryPrefilter prefilter;
    @Mock private MemoryGenToggleService toggleService;
    @Mock private MemoryGenerator generator;
    @Mock private MemoryTagResolver tagResolver;
    @Mock private MemoryTurnMapper turnMapper;
    @Mock private MemoryQueryCache queryCache;
    @Mock private MemoryRoutingService routingService;
    @Mock private TaskExecutor memoryTaskExecutor;

    @InjectMocks
    private MemoryGenerationService service;

    @Captor private ArgumentCaptor<MemoryTurn> turnCaptor;

    private static final FilterResult PASS = new FilterResult(false, false, null, null);
    private static final FilterResult BOTH_SKIP = new FilterResult(true, true, "过短", "套话");
    private static final FilterResult OUTPUT_SKIP = new FilterResult(false, true, null, "套话");

    // ---------- 两侧均跳 ----------

    @Test
    @DisplayName("两侧均被前置过滤跳过 → 0 写入，不调 LLM，不 evict")
    void bothSkipped_noWriteNoLlmNoEvict() {
        when(prefilter.filter(anyString(), anyString())).thenReturn(BOTH_SKIP);

        int n = service.processTurn(1L, 10L, 100L, true, List.of(), "嗯", "您好");

        assertEquals(0, n);
        verify(generator, never()).generate(anyLong(), anyString(), anyString(), any());
        verify(turnMapper, never()).insert(any());
        verify(queryCache, never()).evictUser(anyLong());
    }

    // ---------- gen 关：写 raw ----------

    @Test
    @DisplayName("gen 关 + 双侧过过滤 → 写 2 条 raw turn(gen_done=false)，不调生成器/归一")
    void genOff_writesTwoRawTurns() {
        when(prefilter.filter(anyString(), anyString())).thenReturn(PASS);
        when(toggleService.resolveGenEnabled(1L, 100L)).thenReturn(false);

        int n = service.processTurn(1L, 10L, 100L, true, List.of(), "我住萧山", "好的");

        assertEquals(2, n);
        verify(generator, never()).generate(anyLong(), anyString(), anyString(), any());
        verify(tagResolver, never()).resolve(anyLong(), anyString(), anyString(), anyString());

        verify(turnMapper, times(2)).insert(turnCaptor.capture());
        List<MemoryTurn> turns = turnCaptor.getAllValues();
        assertEquals("INPUT", turns.get(0).getDirection());
        assertEquals("OUTPUT", turns.get(1).getDirection());
        turns.forEach(t -> {
            assertFalse(t.getGenDone(), "gen 关应 gen_done=false");
            assertEquals("我住萧山".equals(t.getRawContent()) || "好的".equals(t.getRawContent()), true);
            assertTrue(t.getTagIds().isEmpty(), "raw turn 无 tag");
            assertNull(t.getL1Summary());
            assertNull(t.getL2Detail());
        });
        verify(queryCache, times(1)).evictUser(1L);
    }

    // ---------- gen 开：写生成层 ----------

    @Test
    @DisplayName("gen 开 + 双侧生成 → 写 2 条 gen_done=true turn，tag 归一 + L1/L2 落库")
    void genOn_bothGenerated() {
        when(prefilter.filter(anyString(), anyString())).thenReturn(PASS);
        when(toggleService.resolveGenEnabled(1L, 100L)).thenReturn(true);
        when(generator.generate(eq(1L), anyString(), anyString(), eq(PASS))).thenReturn(
                new MemoryGenerator.GenResult(
                        new SideLayers("我", "居住", "住址", "住萧山", "萧山区地铁沿线"),
                        new SideLayers("我", "编程", "爬虫", "写Python爬虫", "requests+BS4")));
        when(tagResolver.resolve(anyLong(), anyString(), anyString(), anyString())).thenReturn(7L, 8L);

        int n = service.processTurn(1L, 10L, 100L, true, List.of(), "我住萧山", "写爬虫");

        assertEquals(2, n);
        verify(turnMapper, times(2)).insert(turnCaptor.capture());
        List<MemoryTurn> turns = turnCaptor.getAllValues();
        MemoryTurn in = turns.get(0);
        assertEquals("INPUT", in.getDirection());
        assertTrue(in.getGenDone());
        assertEquals(List.of(7L), in.getTagIds());
        assertEquals("住萧山", in.getL1Summary());
        assertEquals("萧山区地铁沿线", in.getL2Detail());
        assertEquals("我住萧山", in.getRawContent(), "生成 turn 也保留 raw_content（溯源/12h 用）");
        MemoryTurn out = turns.get(1);
        assertEquals(List.of(8L), out.getTagIds());
        verify(queryCache, times(1)).evictUser(1L);
    }

    @Test
    @DisplayName("gen 开 + 仅 INPUT 侧（OUTPUT 被过滤）→ 写 1 条 INPUT turn")
    void genOn_outputSkipped_oneTurn() {
        when(prefilter.filter(anyString(), anyString())).thenReturn(OUTPUT_SKIP);
        when(toggleService.resolveGenEnabled(1L, 100L)).thenReturn(true);
        when(generator.generate(eq(1L), anyString(), anyString(), eq(OUTPUT_SKIP))).thenReturn(
                new MemoryGenerator.GenResult(
                        new SideLayers("我", "居住", "住址", "住萧山", "详情"), null));
        when(tagResolver.resolve(anyLong(), anyString(), anyString(), anyString())).thenReturn(7L);

        int n = service.processTurn(1L, 10L, 100L, true, List.of(), "我住萧山", "很高兴为您服务");

        assertEquals(1, n);
        verify(turnMapper, times(1)).insert(turnCaptor.capture());
        assertEquals("INPUT", turnCaptor.getValue().getDirection());
    }

    @Test
    @DisplayName("gen 开 + 生成器全失败返 null → 过过滤侧写 raw(gen_done=false) 降级")
    void genOn_generatorFailed_writesRaw() {
        when(prefilter.filter(anyString(), anyString())).thenReturn(PASS);
        when(toggleService.resolveGenEnabled(1L, 100L)).thenReturn(true);
        when(generator.generate(anyLong(), anyString(), anyString(), any())).thenReturn(null);

        int n = service.processTurn(1L, 10L, 100L, true, List.of(), "我住萧山", "写爬虫");

        assertEquals(2, n);
        verify(tagResolver, never()).resolve(anyLong(), anyString(), anyString(), anyString());
        verify(turnMapper, times(2)).insert(turnCaptor.capture());
        turnCaptor.getAllValues().forEach(t -> assertFalse(t.getGenDone(), "生成失败应降级 raw"));
    }

    // ---------- born_personal 矩阵 ----------

    @Test
    @DisplayName("born_personal：勾个人 + 项目 → bornPersonal=true + project_ids 叠加")
    void bornPersonal_personalPlusProject() {
        when(prefilter.filter(anyString(), anyString())).thenReturn(PASS);
        when(toggleService.resolveGenEnabled(anyLong(), anyLong())).thenReturn(false);

        service.processTurn(1L, 10L, 100L, true, List.of(200L, 300L), "a", "b");

        verify(turnMapper, times(2)).insert(turnCaptor.capture());
        turnCaptor.getAllValues().forEach(t -> {
            assertTrue(t.getBornPersonal(), "勾个人 → bornPersonal=true");
            assertEquals(List.of(200L, 300L), t.getProjectIds());
        });
    }

    @Test
    @DisplayName("born_personal：仅项目(取消个人) → bornPersonal=false")
    void bornPersonal_projectOnly_false() {
        when(prefilter.filter(anyString(), anyString())).thenReturn(PASS);
        when(toggleService.resolveGenEnabled(anyLong(), anyLong())).thenReturn(false);

        service.processTurn(1L, 10L, 100L, false, List.of(200L), "a", "b");

        verify(turnMapper, times(2)).insert(turnCaptor.capture());
        turnCaptor.getAllValues().forEach(t -> assertFalse(t.getBornPersonal(), "仅项目 → false"));
    }

    @Test
    @DisplayName("卸空(无项目+取消个人) → bornPersonal 自动转 true（防无归属）")
    void bornPersonal_unloadedFlipsToTrue() {
        when(prefilter.filter(anyString(), anyString())).thenReturn(PASS);
        when(toggleService.resolveGenEnabled(anyLong(), anyLong())).thenReturn(false);

        service.processTurn(1L, 10L, 100L, false, List.of(), "a", "b");

        verify(turnMapper, times(2)).insert(turnCaptor.capture());
        turnCaptor.getAllValues().forEach(t -> {
            assertTrue(t.getBornPersonal(), "卸空应自动转 bornPersonal=true");
            assertTrue(t.getProjectIds().isEmpty());
        });
    }

    @Test
    @DisplayName("非项目会话(projectId=null) + 传项目集 → 强制恒个人，projectIds 清空")
    void nonProjectSession_forcedPersonal() {
        when(prefilter.filter(anyString(), anyString())).thenReturn(PASS);
        when(toggleService.resolveGenEnabled(eq(1L), eq(null))).thenReturn(false);

        service.processTurn(1L, 10L, null, false, List.of(200L), "a", "b");

        verify(turnMapper, times(2)).insert(turnCaptor.capture());
        turnCaptor.getAllValues().forEach(t -> {
            assertTrue(t.getBornPersonal(), "非项目会话恒个人出身");
            assertTrue(t.getProjectIds().isEmpty(), "非项目会话 projectIds 清空");
        });
    }

    // ---------- 异步包装 ----------

    @Test
    @DisplayName("processTurnAsync 提交 Runnable 到 executor（不阻塞调用方）")
    void async_submitsToExecutor() {
        service.processTurnAsync(1L, 10L, 100L, true, List.of(), "我住萧山", "好的");

        // 提交了任务，但 processTurn 尚未执行（executor 是 mock）
        verify(memoryTaskExecutor, times(1)).execute(any(Runnable.class));
        verify(prefilter, never()).filter(anyString(), anyString());
    }

    @Test
    @DisplayName("processTurnAsync 提交的 Runnable 执行后等价于同步 processTurn")
    void async_runnableDelegates() {
        when(prefilter.filter(anyString(), anyString())).thenReturn(PASS);
        when(toggleService.resolveGenEnabled(anyLong(), anyLong())).thenReturn(false);

        // 捕获 Runnable 并同步执行（模拟 executor 真跑）
        org.mockito.ArgumentCaptor<Runnable> rc = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        service.processTurnAsync(1L, 10L, 100L, true, List.of(), "我住萧山", "好的");
        verify(memoryTaskExecutor).execute(rc.capture());
        rc.getValue().run();

        verify(turnMapper, times(2)).insert(any());
        verify(queryCache).evictUser(1L);
    }
}
