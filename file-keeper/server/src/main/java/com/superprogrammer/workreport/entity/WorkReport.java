package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("work_reports")
public class WorkReport extends BaseEntity {

    private Long userId;

    private Long configId;

    private String reportType;

    private String title;

    private String content;

    private OffsetDateTime generatedAt;

    private String status;
}
