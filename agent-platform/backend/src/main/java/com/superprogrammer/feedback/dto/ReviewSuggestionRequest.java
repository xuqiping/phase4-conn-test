package com.superprogrammer.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** admin 审核建议请求。ADOPTED↔REJECTED 可改判（重发通知）；CLOSED 终态不可入也不可达。 */
public record ReviewSuggestionRequest(
        @NotBlank(message = "审核结论必填")
        @Pattern(regexp = "ADOPTED|REJECTED|CLOSED", message = "结论仅支持 ADOPTED/REJECTED/CLOSED")
        String toStatus,
        @Size(max = 2000, message = "回复最长 2000 字") String reply) {
}
