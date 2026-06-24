package com.superprogrammer.settings.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("system_settings")
public class SystemSetting extends BaseEntity {

    private String settingKey;
    private String settingValue;
    private String description;
}
