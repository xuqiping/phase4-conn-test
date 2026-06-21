package com.superprogrammer.knowledge.service.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * BATCH 模式 LLM 单次调用返回的 JSON 结构（解析目标）。
 * Jackson 反序列化；malformed 由 DocumentParserService 容错降级，不抛。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchLlmResult {

    private String summary;

    private List<String> outline;

    private List<String> importantRules;

    private List<BatchSection> sections;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchSection {

        private String title;

        /** JSON 字段名为 "abstract"，Java 关键字 → 用 abstractText 承载 */
        @JsonProperty("abstract")
        private String abstractText;
    }
}
