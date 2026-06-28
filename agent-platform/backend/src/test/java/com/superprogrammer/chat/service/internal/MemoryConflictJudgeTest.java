package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.service.internal.ExtractedFact;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
}
