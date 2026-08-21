package com.superprogrammer.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 提交建议请求（19x#1）。附件 ≤3，逐 fileId 属主校验在 service 层。 */
public record CreateSuggestionRequest(
        @NotBlank(message = "标题必填") @Size(max = 120, message = "标题最长 120 字") String title,
        @NotBlank(message = "内容必填") @Size(max = 4000, message = "内容最长 4000 字") String content,
        @Size(max = 3, message = "附件最多 3 个") List<String> attachmentFileIds) {
}
