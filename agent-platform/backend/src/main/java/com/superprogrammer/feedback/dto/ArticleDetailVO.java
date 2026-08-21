package com.superprogrammer.feedback.dto;

import java.time.OffsetDateTime;

/** 用户文章详情（markdown 原文，前端 renderMarkdown html:false 渲染）。 */
public record ArticleDetailVO(String slug,
                              String title,
                              String category,
                              String contentMd,
                              OffsetDateTime publishedAt) {
}
