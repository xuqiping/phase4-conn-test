package com.superprogrammer.feedback.dto;

import java.time.OffsetDateTime;

/**
 * 留言线程行（用户/admin 共用）。senderRole 驱动前端展示（ADMIN→管理员，USER→我）。
 */
public record FeedbackMessageVO(Long id, String senderRole, String content, OffsetDateTime createdAt) {
}
