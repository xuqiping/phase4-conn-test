package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.service.internal.MemoryConsolidationCompressor.CompressedSummary;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 计划12 · E-3a · MemoryConsolidationCompressor 单测。
 * 重点：日期铁律断言（年份必匹配源 turn）+ applyClean 解析 + fail-safe null。
 */
@ExtendWith(MockitoExtension.class)
class MemoryConsolidationCompressorTest {

    @Mock LlmGateway llmGateway;

    @Mock
    com.superprogrammer.system.service.SystemSettingService systemSettingService;

    private MemoryConsolidationCompressor compressor;

    @BeforeEach
    void setUp() {
        lenient().when(systemSettingService.getMemoryJudgeModel()).thenReturn("doubao-seed-2.0-code");
        compressor = new MemoryConsolidationCompressor(llmGateway, new ObjectMapper(), systemSettingService);
    }

    private static MemoryTurn turn(Long id, String l1, String year) {
        MemoryTurn t = new MemoryTurn();
        t.setId(id);
        t.setDirection("INPUT");
        t.setL1Summary(l1);
        t.setCreatedAt(OffsetDateTime.parse(year + "-03-15T10:00:00+08:00"));
        return t;
    }

    // ---- 1. 日期铁律：源年份被包含 → 放行 ----

    @Test
    void yearIronRuleSourceYearPresentPasses() {
        when(llmGateway.chat(any(), any(Long.class))).thenReturn(LlmResponse.builder().content(
                "{\"l1\":\"2026 入职\",\"l2\":\"2026 年加入后端组\"}").build());

        CompressedSummary s = compressor.compress(1L, "工作", List.of(turn(1L, "入职", "2026")));

        assertNotNull(s);
        assertEquals("2026 入职", s.l1());
        assertTrue(s.sourceTurnIds().contains(1L));
    }

    // ---- 2. 日期铁律：LLM 编源外年份 → 违则重试至 null（3 次均违则）----

    @Test
    void yearIronRuleHallucinatedYearRetriesToNull() {
        when(llmGateway.chat(any(), any(Long.class))).thenReturn(LlmResponse.builder().content(
                "{\"l1\":\"2019 入职\",\"l2\":\"2019 加入\"}").build());

        CompressedSummary s = compressor.compress(1L, "工作", List.of(turn(1L, "入职", "2026")));

        assertNull(s, "源年份 2026，LLM 编 2019 → 铁律违则，3 次重试后 null");
    }

    // ---- 3. 源无年份（created_at null）→ 放行（无法校验）----

    @Test
    void yearIronRuleNoSourceYearPasses() {
        MemoryTurn t = new MemoryTurn();
        t.setId(1L); t.setDirection("INPUT"); t.setL1Summary("会 Java");
        when(llmGateway.chat(any(), any(Long.class))).thenReturn(LlmResponse.builder().content(
                "{\"l1\":\"擅长 Java\",\"l2\":\"后端主语言\"}").build());

        CompressedSummary s = compressor.compress(1L, "技能", List.of(t));

        assertNotNull(s, "源无年份不强制加年份，放行");
    }

    // ---- 4. 解析 applyClean：strip fence + 前后塞文字 ----

    @Test
    void applyCleanStripsFenceAndNoise() {
        when(llmGateway.chat(any(), any(Long.class))).thenReturn(LlmResponse.builder().content(
                "好的，结果如下：\n```json\n{\"l1\":\"概要\",\"l2\":\"详述\"}\n```\n完成").build());

        CompressedSummary s = compressor.compress(1L, "t", List.of(turn(1L, "x", "2026")));

        assertNotNull(s);
        assertEquals("概要", s.l1());
    }

    // ---- 5. L1+L2 全空 → null ----

    @Test
    void bothEmptyReturnsNull() {
        when(llmGateway.chat(any(), any(Long.class))).thenReturn(LlmResponse.builder().content(
                "{\"l1\":\"\",\"l2\":\"\"}").build());

        CompressedSummary s = compressor.compress(1L, "t", List.of(turn(1L, "x", "2026")));

        assertNull(s);
    }

    // ---- 6. turns 空 → 直接 null，不调 LLM ----

    @Test
    void emptyTurnsReturnsNullNoLlm() {
        CompressedSummary s = compressor.compress(1L, "t", List.of());
        assertNull(s);
    }

    // ---- 7. LLM 异常 → fail-safe null（不抛）----

    @Test
    void llmThrowsReturnsNull() {
        when(llmGateway.chat(any(), any(Long.class))).thenThrow(new RuntimeException("timeout"));

        CompressedSummary s = compressor.compress(1L, "t", List.of(turn(1L, "x", "2026")));

        assertNull(s);
    }

    // ---- 8. assertYearIronRule 单元：总结无年份放行 + 源外年份拒 ----

    @Test
    void assertYearIronRuleDirectLogic() {
        Set<Integer> source = Set.of(2026);
        // 总结无年份 → 放行
        assertTrue(compressor.assertYearIronRule(new CompressedSummary("会 Java", "", List.of()), source));
        // 源内年份 → 放行
        assertTrue(compressor.assertYearIronRule(new CompressedSummary("2026 入职", "", List.of()), source));
        // 源外年份 → 拒
        assertFalse(compressor.assertYearIronRule(new CompressedSummary("2019 入职", "", List.of()), source));
        // 源空 → 放行
        assertTrue(compressor.assertYearIronRule(new CompressedSummary("2019 入职", "", List.of()), Set.of()));
    }
}
