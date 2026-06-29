package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.entity.UserMemory;
import com.superprogrammer.chat.service.internal.ExtractedFact;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MemoryConflictJudge 抽取/解析单测（真 judge + mock LlmGateway，验 prompt→parse 链路）。
 * <p>F1/F2：entities 含上位词（家人/工作/健康/居住地）+ readEntities ≤10 截断。
 */
@ExtendWith(MockitoExtension.class)
class MemoryConflictJudgeTest {

    @Mock private LlmGateway llmGateway;

    private MemoryConflictJudge judge;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        judge = new MemoryConflictJudge(llmGateway, objectMapper);
    }

    @Test
    void extract_entitiesIncludeHypernymAndCapAt10() {
        // entities 含上位词"家人"（F1）+ 12 个词测 ≤10 截断（readEntities 上限）
        String json = """
                [{"category":"FACT","key":"child_name","key_zh":"女儿","value":"啊闪","confidence":0.9,
                  "block":"家庭信息","entities":["女儿","孩子","小孩","闺女","家人","啊闪","宝","娃","小","宝贝","心肝","天使"]}]""";
        when(llmGateway.chat(any())).thenReturn(LlmResponse.builder().content(json).build());

        List<ExtractedFact> facts = judge.extract("我女儿叫啊闪", "好的，记下了", List.of());

        assertEquals(1, facts.size());
        List<String> ents = facts.get(0).entities();
        assertNotNull(ents);
        assertTrue(ents.contains("家人"), "entities 含上位词「家人」（F1 泛称召回）");
        assertTrue(ents.contains("啊闪"), "entities 含 value 专名");
        assertTrue(ents.size() <= 10, "readEntities 上限 10（F2 截断）");
    }

    @Test
    void extract_emptyEntitiesArray_returnsEmpty() {
        String json = "[{\"category\":\"FACT\",\"key\":\"misc\",\"value\":\"x\",\"confidence\":0.5,\"entities\":[]}]";
        when(llmGateway.chat(any())).thenReturn(LlmResponse.builder().content(json).build());

        List<ExtractedFact> facts = judge.extract("随便", "ok", List.of());

        assertEquals(1, facts.size());
        assertTrue(facts.get(0).entities() == null || facts.get(0).entities().isEmpty());
    }

    @Test
    void selectRelevantKeysBlocks_oneCallThreeDims() {
        // 三维合并：一次 chat 调用产出 keys/keys_zh/blocks 三个数组
        String json = """
                {"keys":["spouse_name","child_name"],"keys_zh":["妻子","女儿"],"blocks":["家庭信息"]}""";
        when(llmGateway.chat(any())).thenReturn(LlmResponse.builder().content(json).build());

        UserMemory spouse = um(193L, "spouse_name", "妻子", "阿斐", "家庭信息");
        UserMemory child = um(1L, "child_name", "女儿", "小红", "家庭信息");
        UserMemory work = um(2L, "occupation", "职业", "工程师", "职业");

        MemoryConflictJudge.RelevantDims d = judge.selectRelevantKeysBlocks(
                "我想带我家人在附近逛逛",
                List.of(spouse, child, work),
                List.of("家庭信息", "职业"));

        assertNotNull(d);
        assertEquals(Set.of("spouse_name", "child_name"), d.keys());
        assertEquals(Set.of("妻子", "女儿"), d.keysZh());
        assertEquals(Set.of("家庭信息"), d.blocks());
        verify(llmGateway, times(1)).chat(any());   // 三维合并仅一次 LLM 调用（砍第二次）
    }

    @Test
    void selectRelevantKeysBlocks_llmEmpty_failSafeNull() {
        when(llmGateway.chat(any())).thenReturn(LlmResponse.builder().content("").build());

        MemoryConflictJudge.RelevantDims d = judge.selectRelevantKeysBlocks(
                "q", List.of(um(1L, "k", "标签", "v", "块")), List.of("块"));

        assertNull(d);   // LLM 失败/空 → null（上层不注入）
    }

    private UserMemory um(long id, String key, String zh, String val, String block) {
        UserMemory m = new UserMemory();
        m.setId(id);
        m.setMemoryKey(key);
        m.setMemoryKeyZh(zh);
        m.setMemoryValue(val);
        m.setBlockLabel(block);
        m.setConfidence(new BigDecimal("0.9"));
        return m;
    }
}
