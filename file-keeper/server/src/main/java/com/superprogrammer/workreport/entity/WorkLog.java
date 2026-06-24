package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("work_logs")
public class WorkLog extends BaseEntity {

    private Long userId;

    private LocalDate logDate;

    private String content;

    private String tags;

    private String source;

    private Integer sortOrder;
}
