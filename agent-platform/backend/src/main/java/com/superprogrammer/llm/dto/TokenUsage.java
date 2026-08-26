package com.superprogrammer.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用量（Provider 层已归一，V160 D3 / 9x-1）：
 * <ul>
 *   <li>{@code promptTokens} = <b>计费输入基数</b>，非协议原始值——
 *       OpenAI 系 = prompt_tokens − cached_tokens（未命中输入）；
 *       Claude 系 = input_tokens + cache_creation_input_tokens（写入溢价按普通输入并入，1.25 倍不建模，规格取舍）。</li>
 *   <li>{@code cachedTokens} = 缓存命中读 token（OpenAI=prompt_tokens_details.cached_tokens；
 *       Claude=cache_read_input_tokens）；null=协议未上报（计费退化两腿）。</li>
 *   <li>{@code totalTokens} = 协议上报总token（信息字段，OpenAI 原值；Claude=input+output），
 *       与前两者无严格加和关系。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsage {
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    /** 缓存命中读 token；null=未上报/不支持（计费第三腿消失，与老口径一致）。 */
    private Long cachedTokens;
}
