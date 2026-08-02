package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("report_config_push_targets")
public class ReportConfigPushTargetRef extends BaseEntity {

    private Long configId;

    private Long targetId;
}
