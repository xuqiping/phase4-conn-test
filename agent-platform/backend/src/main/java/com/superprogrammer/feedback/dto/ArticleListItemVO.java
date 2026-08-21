package com.superprogrammer.feedback.dto;

import java.time.OffsetDateTime;

/** 用户目录行（说明台左侧列表）：刻意无 content_md 大字段。 */
public record ArticleListItemVO(String slug,
                                String title,
                                String category,
                                Integer sortOrder,
                                OffsetDateTime publishedAt) {
}
