package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("future_plans")
public class FuturePlan extends BaseEntity {

    private Long userId;

    private String content;

    private String description;

    private OffsetDateTime scheduledAt;

    private String timezone;

    private Boolean reminderEnabled;

    private Integer reminderMinutesBefore;

    private String pushPlatform;

    private String pushTargetId;

    private String pushCredential;

    private String status;

    private Integer sortOrder;
}
