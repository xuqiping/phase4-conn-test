package com.superprogrammer.feedback.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 反馈站内通知（feedback_notifications，V141）。
 * <p>审核/回答结果推用户；未读 count 走部分索引（read_at IS NULL）。
 * message 纯文本（前端不渲染 HTML）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("feedback_notifications")
public class FeedbackNotificationEntity extends BaseEntity {

    public static final String TYPE_SUGGESTION_REVIEWED = "SUGGESTION_REVIEWED";
    public static final String TYPE_QUESTION_ANSWERED = "QUESTION_ANSWERED";
    /** V154：admin 留言通知（每条留言都发，与审核/首答的一次性语义不同）。 */
    public static final String TYPE_SUGGESTION_MESSAGE = "SUGGESTION_MESSAGE";
    public static final String TYPE_QUESTION_MESSAGE = "QUESTION_MESSAGE";

    private Long userId;

    private String type;

    /** 关联建议/提问 id（点击跳对应 tab）。 */
    private Long refId;

    private String message;

    private OffsetDateTime readAt;
}
