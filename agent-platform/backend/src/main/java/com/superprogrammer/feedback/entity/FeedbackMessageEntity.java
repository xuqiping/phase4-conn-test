package com.superprogrammer.feedback.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 反馈留言线程（feedback_messages，V154）。
 * <p>19x 未解决#1：审核/回答后 admin 可继续给用户留言；每次留言都触发站内通知
 * （SUGGESTION_MESSAGE / QUESTION_MESSAGE）。sender_role=USER 预留（未来开放用户回复时通知方向反之）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("feedback_messages")
public class FeedbackMessageEntity extends BaseEntity {

    public static final String TARGET_SUGGESTION = "SUGGESTION";
    public static final String TARGET_QUESTION = "QUESTION";

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    /** 留言目标类型：SUGGESTION / QUESTION。 */
    private String targetType;

    /** 目标 id（feedback_suggestions / feedback_questions 主键，应用层校验存在性）。 */
    private Long targetId;

    private Long senderId;

    /** 发送方角色：ADMIN / USER（决定通知方向）。 */
    private String senderRole;

    private String content;
}
