package com.superprogrammer.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** admin 回答提问请求。isPublic=true 即公开为 FAQ（随答案落库；取消公开同端点）。 */
public record AnswerQuestionRequest(
        @NotBlank(message = "答案必填") @Size(max = 8000, message = "答案最长 8000 字") String answer,
        @NotNull(message = "isPublic 必填") Boolean isPublic) {
}
