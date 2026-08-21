package com.superprogrammer.feedback.dto;

import java.time.OffsetDateTime;

/** 用户侧提问行（我的提问；含 admin markdown 答案）。 */
public record QuestionVO(Long id,
                         OffsetDateTime createdAt,
                         String title,
                         String content,
                         String status,
                         String answer,
                         OffsetDateTime answeredAt) {
}
