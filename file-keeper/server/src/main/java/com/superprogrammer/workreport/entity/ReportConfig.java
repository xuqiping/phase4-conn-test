package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("report_configs")
public class ReportConfig extends BaseEntity {

    private Long userId;

    private String name;

    private String reportType;

    private Long templateId;

    private String cronExpression;

    private String timezone;

    private Boolean enabled;

    private Boolean aiEnabled;
}
