package com.superprogrammer.feedback.dto;

import java.time.OffsetDateTime;

/** admin 提问行（多 userId/username 快照 + isPublic）。 */
public record AdminQuestionVO(Long id,
                              OffsetDateTime createdAt,
                              Long userId,
                              String username,
                              String title,
                              String content,
                              String status,
                              String answer,
                              Boolean isPublic,
                              OffsetDateTime answeredAt) {
}
