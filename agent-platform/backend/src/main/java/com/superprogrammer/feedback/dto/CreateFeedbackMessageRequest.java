package com.superprogrammer.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * admin 留言请求（建议/提问共用，目标由路径决定）。
 */
public record CreateFeedbackMessageRequest(
        @NotBlank(message = "留言内容不能为空")
        @Size(max = 2000, message = "留言最长 2000 字")
        String content) {
}
