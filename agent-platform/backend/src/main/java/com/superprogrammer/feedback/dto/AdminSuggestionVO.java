package com.superprogrammer.feedback.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** admin 建议行（比用户 VO 多 userId/username 快照）。 */
public record AdminSuggestionVO(Long id,
                                OffsetDateTime createdAt,
                                Long userId,
                                String username,
                                String title,
                                String content,
                                List<String> attachmentFileIds,
                                String status,
                                String reply,
                                OffsetDateTime reviewedAt) {
}
