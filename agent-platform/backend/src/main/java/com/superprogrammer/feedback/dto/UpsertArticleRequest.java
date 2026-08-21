package com.superprogrammer.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** admin 新建/更新帮助文章请求。slug 创建后不可改（更新时忽略本字段，防外链失锚）。 */
public record UpsertArticleRequest(
        @NotBlank(message = "slug 必填")
        @Pattern(regexp = "^[a-z0-9-]+$", message = "slug 仅允许小写字母/数字/连字符")
        @Size(max = 80, message = "slug 最长 80 字符") String slug,
        @NotBlank(message = "标题必填") @Size(max = 120, message = "标题最长 120 字") String title,
        @Size(max = 40, message = "分类最长 40 字") String category,
        Integer sortOrder,
        @NotBlank(message = "正文必填") String contentMd) {
}
