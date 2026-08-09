package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.service.internal.MemoryPrefilter.FilterResult;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 计划12 · C · 记忆生成器单测（Mockito，LlmGateway mock，ObjectMapper real）。
 * 出口对齐 plan C：双侧三层同生 / applyClean 兜底 / 重试 / 脏 JSON 走兜底不抛。
 */
@ExtendWith(MockitoExtension.class)
class MemoryGeneratorTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private MemoryGenerator generator;
    // ObjectMapper 注入：MemoryGenerator 用构造注入；@InjectMocks 默认对 final 字段需匹配构造。
    // 下面用例前手动通过反射设置 objectMapper（避开 Spring 容器）。

    private final ObjectMapper objectMapper = new ObjectMapper();

    private void wireObjectMapper() throws Exception {
        java.lang.reflect.Field f = MemoryGenerator.class.getDeclaredField("objectMapper");
        f.setAccessible(true);
        f.set(generator, objectMapper);
    }

    private void mockChatReturn(Long userId, String content) {
        when(llmGateway.chat(any(LlmRequest.class), eq(userId)))
                .thenReturn(LlmResponse.builder().content(content).build());
    }

    private static final String BOTH_JSON = """
            {
              "input": {"subject":"我","topic":"居住","label":"住址","l1":"住萧山","l2":"萧山区地铁沿线"},
              "output": {"subject":"我","topic":"编程","label":"爬虫","l1":"写Python爬虫","l2":"requests+BS4+重试"}
            }""";

    @Test
    @DisplayName("双侧未跳 + 合法 JSON → 双侧三层")
    void bothSides_validJson() throws Exception {
        wireObjectMapper();
        Long userId = 1L;
        mockChatReturn(userId, BOTH_JSON);
        FilterResult pass = new FilterResult(false, false, null, null);

        MemoryGenerator.GenResult r = generator.generate(userId, "我住萧山", "写爬虫代码", pass, "doubao-seed-2.0-code");

        assertNotNull(r);
        assertNotNull(r.input());
        assertEquals("居住", r.input().topic());
        assertEquals("住萧山", r.input().l1Summary());
        assertEquals("萧山区地铁沿线", r.input().l2Detail());
        assertNotNull(r.output());
        assertEquals("爬虫", r.output().label());
    }

    @Test
    @DisplayName("仅 INPUT 侧（OUTPUT 被过滤）→ 只产 input，output null")
    void onlyInputSide() throws Exception {
        wireObjectMapper();
        Long userId = 1L;
        when(llmGateway.chat(any(LlmRequest.class), eq(userId)))
                .thenAnswer(inv -> {
                    // 验证 prompt 只要求 input 侧（不含 output 文本）
                    LlmRequest req = inv.getArgument(0);
                    String c = req.getMessages().get(0).getContent();
                    assertTrue(c.contains("我住萧山"), "prompt 应含 INPUT 文本");
                    return LlmResponse.builder().content(
                            "{\"input\":{\"subject\":\"我\",\"topic\":\"居住\",\"label\":\"住址\",\"l1\":\"住萧山\",\"l2\":\"详情\"}}"
                    ).build();
                });
        FilterResult outputSkipped = new FilterResult(false, true, null, "套话");

        MemoryGenerator.GenResult r = generator.generate(userId, "我住萧山", "很高兴为您服务", outputSkipped, "doubao-seed-2.0-code");

        assertNotNull(r);
        assertNotNull(r.input());
        assertNull(r.output(), "OUTPUT 被过滤不应生成");
    }

    @Test
    @DisplayName("脏 JSON（fence 包裹 + 前后塞文字）→ applyClean 提取成功")
    void dirtyJson_fenced() throws Exception {
        wireObjectMapper();
        Long userId = 1L;
        String dirty = "好的，结果如下：\n```json\n" + BOTH_JSON + "\n```\n以上是记忆。";
        mockChatReturn(userId, dirty);
        FilterResult pass = new FilterResult(false, false, null, null);

        MemoryGenerator.GenResult r = generator.generate(userId, "我住萧山", "写爬虫", pass, "doubao-seed-2.0-code");

        assertNotNull(r, "脏 JSON 走 applyClean 兜底应解析成功");
        assertEquals("居住", r.input().topic());
    }

    @Test
    @DisplayName("LLM 返回非 JSON + 3 次重试全失败 → null（写 raw 降级）")
    void malformed_allRetriesNull() throws Exception {
        wireObjectMapper();
        Long userId = 1L;
        when(llmGateway.chat(any(LlmRequest.class), eq(userId)))
                .thenReturn(LlmResponse.builder().content("这不是JSON，是无法解析的自然语言").build());
        FilterResult pass = new FilterResult(false, false, null, null);

        MemoryGenerator.GenResult r = generator.generate(userId, "我住萧山", "写爬虫", pass, "doubao-seed-2.0-code");

        assertNull(r, "全失败应返 null 让上层写 raw genDone=false");
    }

    @Test
    @DisplayName("必要侧 topic 缺失（LLM 判无信息）→ 解析失败 → 重试后 null")
    void requiredSideTopicNull_fails() throws Exception {
        wireObjectMapper();
        Long userId = 1L;
        // input 侧 topic=null（LLM 主动表态「无信息可提」）→ schema 校验失败
        String json = "{\"input\":{\"subject\":\"我\",\"topic\":null,\"label\":null,\"l1\":\"\",\"l2\":\"\"}}";
        when(llmGateway.chat(any(LlmRequest.class), eq(userId)))
                .thenReturn(LlmResponse.builder().content(json).build());
        FilterResult inputOnly = new FilterResult(false, true, null, null);

        MemoryGenerator.GenResult r = generator.generate(userId, "嗯", "好的", inputOnly, "doubao-seed-2.0-code");

        assertNull(r, "必要侧 topic/label 缺失应视为生成失败");
    }

    @Test
    @DisplayName("LLM 调用抛异常 → 重试 → 全失败 null")
    void llmThrows_retriesNull() throws Exception {
        wireObjectMapper();
        Long userId = 1L;
        when(llmGateway.chat(any(LlmRequest.class), eq(userId)))
                .thenThrow(new RuntimeException("provider down"));
        FilterResult pass = new FilterResult(false, false, null, null);

        MemoryGenerator.GenResult r = generator.generate(userId, "我住萧山", "写爬虫", pass, "doubao-seed-2.0-code");

        assertNull(r);
    }

    @Test
    @DisplayName("两侧都被过滤（bothSkipped）→ 空 GenResult（不调 LLM）")
    void bothSkipped_emptyNoLlm() throws Exception {
        wireObjectMapper();
        FilterResult both = new FilterResult(true, true, "过短", "套话");

        MemoryGenerator.GenResult r = generator.generate(1L, "嗯", "您好", both, "doubao-seed-2.0-code");

        assertNotNull(r);
        assertTrue(r.isEmpty(), "两侧跳过应返回空结果");
        assertNull(r.input());
        assertNull(r.output());
    }

    @Test
    @DisplayName("L2 缺失但 topic/label 齐 → 容忍（l2 默认空串，仍算成功）")
    void l2Missing_tolerated() throws Exception {
        wireObjectMapper();
        Long userId = 1L;
        String json = "{\"input\":{\"subject\":\"我\",\"topic\":\"居住\",\"label\":\"住址\",\"l1\":\"住萧山\"}}";
        when(llmGateway.chat(any(LlmRequest.class), eq(userId)))
                .thenReturn(LlmResponse.builder().content(json).build());
        FilterResult inputOnly = new FilterResult(false, true, null, null);

        MemoryGenerator.GenResult r = generator.generate(userId, "我住萧山", "好的", inputOnly, "doubao-seed-2.0-code");

        assertNotNull(r);
        assertNotNull(r.input());
        assertEquals("住萧山", r.input().l1Summary());
        assertEquals("", r.input().l2Detail(), "L2 缺失默认空串");
        assertFalse(r.input().l2Detail() == null);
    }
}
