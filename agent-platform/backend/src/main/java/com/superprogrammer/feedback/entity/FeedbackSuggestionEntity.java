package com.superprogrammer.feedback.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 建议台（feedback_suggestions，V141）。
 * <p>状态机：PENDING →ADOPTED/REJECTED/CLOSED；ADOPTED↔REJECTED 可改判（重发通知），CLOSED 终态。
 * 翻转全走条件 UPDATE 抢态（同 V140 payment 先例）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "feedback_suggestions", autoResultMap = true)
public class FeedbackSuggestionEntity extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ADOPTED = "ADOPTED";
    public static final String STATUS_REJECTED = "REJECTED";
    /** 终态（不可改判）。 */
    public static final String STATUS_CLOSED = "CLOSED";

    private Long userId;

    /** 提交时用户名快照（改名不追溯）。 */
    private String username;

    private String title;

    private String content;

    /** 截图附件 fileId 数组（≤3，提交时逐 id 校验属主）；JSON 字符串如 "[12,34]"。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String attachmentFileIds;

    private String status;

    private String reply;

    private Long reviewedBy;

    private OffsetDateTime reviewedAt;
}
