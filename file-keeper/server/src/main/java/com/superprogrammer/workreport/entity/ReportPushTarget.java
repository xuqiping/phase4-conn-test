package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("report_push_targets")
public class ReportPushTarget extends BaseEntity {

    private Long configId;

    private String platform;

    private String targetType;

    private String targetId;

    private String credential;
}
