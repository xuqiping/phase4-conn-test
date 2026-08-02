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
public class LlmMessage {
    private String role;
    private String content;

    /**
     * 多模态内容片段（v6 阶段2）。非空时 provider 发 content 数组（Claude image base64 / OpenAI image_url）；
     * null/空走老 {@link #content} 字符串路径，零行为变化。
     */
    private List<ContentPart> parts;

    /** 旧 2 参构造（仅 role/content，parts=null）保留向后兼容，如 Provider 连通性测试。 */
    public LlmMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
