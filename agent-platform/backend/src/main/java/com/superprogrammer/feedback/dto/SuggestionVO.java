package com.superprogrammer.feedback.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 用户侧建议行（我的建议列表；含 admin 回复）。 */
public record SuggestionVO(Long id,
                           OffsetDateTime createdAt,
                           String title,
                           String content,
                           List<String> attachmentFileIds,
                           String status,
                           String reply,
                           OffsetDateTime reviewedAt) {
}
