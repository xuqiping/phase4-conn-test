package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("report_templates")
public class ReportTemplate extends BaseEntity {

    private Long userId;

    private String name;

    private String type;

    private String content;

    private Boolean isDefault;
}
