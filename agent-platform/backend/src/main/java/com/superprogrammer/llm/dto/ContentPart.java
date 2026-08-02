package com.superprogrammer.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 多模态消息内容片段（v6 阶段2 第1项）。配合 {@link LlmMessage#getParts()} 使用：
 * <ul>
 *   <li>{@code type="text"}  → {@link #getText()} 为文本片段</li>
 *   <li>{@code type="image"} → {@link #getData()} 为 base64（无 data: 前缀），{@link #getMediaType()} 为 MIME 如 image/png</li>
 * </ul>
 * {@link LlmMessage#getParts()} 为 null/空时走老 {@link LlmMessage#getContent()} 字符串路径（零行为变化）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentPart {
    /** "text" | "image" */
    private String type;
    /** text 片段的文本内容 */
    private String text;
    /** image 片段的 base64（无 data:image/...;base64, 前缀，纯 base64） */
    private String data;
    /** image 的 MIME，如 image/png、image/jpeg */
    private String mediaType;
}
