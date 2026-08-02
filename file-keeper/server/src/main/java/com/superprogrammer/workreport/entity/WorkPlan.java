package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("work_plans")
public class WorkPlan extends BaseEntity {

    private Long userId;

    private LocalDate planDate;

    private String content;

    private String description;

    private String priority;

    private LocalTime plannedStartTime;

    private LocalTime plannedEndTime;

    private Boolean completed;

    private Integer sortOrder;
}
