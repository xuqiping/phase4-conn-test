package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fixed_work_items")
public class FixedWorkItem extends BaseEntity {

    private Long userId;

    private String content;

    private String description;

    private String recurrenceType;

    private LocalTime reminderTime;

    private String reminderDays;

    private String timezone;

    private Boolean reminderEnabled;

    private String legacyPushPlatform;

    private String legacyPushTargetId;

    private String legacyPushCredential;

    private Long pushTargetId;

    private Integer sortOrder;
}
