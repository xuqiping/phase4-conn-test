package com.superprogrammer.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {
    /** 显式模型；为空时由 LlmGateway 使用管理员配置的默认对话模型。 */
    private String model;
    private List<LlmMessage> messages;
    @Builder.Default
    private Double temperature = 0.7;
    @Builder.Default
    private Integer maxTokens = 4096;
    @Builder.Default
    private Boolean stream = false;
    /** 单次非流式请求的局部超时；null 表示使用 Provider 默认值。 */
    private Integer timeoutMs;
    /** RAG trace purpose; null uses the gateway's endpoint default. */
    private String callPurpose;
}
