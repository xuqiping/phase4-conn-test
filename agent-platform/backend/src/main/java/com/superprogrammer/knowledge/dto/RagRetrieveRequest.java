package com.superprogrammer.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class RagRetrieveRequest {

    @NotNull
    private Long kbId;

    @NotBlank
    private String query;

    /** metadata 硬 pre-filter：policy/manual/faq/...；空 = 不过滤 */
    private List<String> docTypes;

    /** 覆盖 dense top-N；空 = 用 RagConfig.maxL0Candidates */
    private Integer maxL0;

    /** ECONOMY / BALANCED / PRECISION（Phase1 仅 BALANCED）*/
    private String mode;

    /**
     * 是否生成答案（调 LLM）。
     * false（默认）= 纯检索调试：只返回候选 L0 / 证据 L2 / token 预算，不调 generate，秒级返回。
     * true = 完整 RAG：调 LLM 生成带 [n] 引用的答案（慢，10s+）。
     * 检索调试默认 false；要答案去 /ask（SSE 流式）。
     */
    private boolean generateAnswer;

    /** controller 注入（非请求字段）：当前用户是否 admin */
    private boolean adminHint;
}
