package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("reminder_deliveries")
public class ReminderDelivery extends BaseEntity {

    private String sourceType;

    private Long sourceId;

    private Long userId;

    private String platform;

    private String targetId;

    private String credential;

    private Long pushTargetId;

    private String status;

    private String response;

    private Integer triedCount;
}
