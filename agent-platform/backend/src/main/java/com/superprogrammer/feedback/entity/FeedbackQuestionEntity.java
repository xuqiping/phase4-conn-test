package com.superprogrammer.feedback.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 提问台（feedback_questions，V141）。
 * <p>状态机：OPEN →ANSWERED（首次落 ANSWERED 发通知；ANSWERED→ANSWERED 改答案不重发）→CLOSED（终态不可答）。
 * is_public=true 即 FAQ 公开（公开视图 SQL 不 SELECT username——脱敏在字段不存在层做）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("feedback_questions")
public class FeedbackQuestionEntity extends BaseEntity {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_ANSWERED = "ANSWERED";
    /** 终态（不可再答）。 */
    public static final String STATUS_CLOSED = "CLOSED";

    private Long userId;

    /** 提交时用户名快照（仅 admin 视图用；FAQ 查询不 SELECT 本列）。 */
    private String username;

    private String title;

    private String content;

    private String status;

    /** admin markdown 回答原文（渲染侧 html:false 防 XSS）。 */
    private String answer;

    private Boolean isPublic;

    private Long answeredBy;

    private OffsetDateTime answeredAt;
}
