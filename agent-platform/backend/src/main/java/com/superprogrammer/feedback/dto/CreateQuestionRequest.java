package com.superprogrammer.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 用户提问请求（19x#2）。 */
public record CreateQuestionRequest(
        @NotBlank(message = "标题必填") @Size(max = 120, message = "标题最长 120 字") String title,
        @NotBlank(message = "内容必填") @Size(max = 4000, message = "内容最长 4000 字") String content) {
}
