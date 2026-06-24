package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fixed_work_completions")
public class FixedWorkCompletion extends BaseEntity {

    private Long itemId;

    private Long userId;

    private LocalDate completionDate;

    private Boolean completed;

    private OffsetDateTime completedAt;
}
