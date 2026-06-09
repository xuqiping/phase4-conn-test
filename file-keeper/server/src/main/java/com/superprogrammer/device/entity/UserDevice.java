package com.superprogrammer.device.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_devices")
public class UserDevice extends BaseEntity {

    private Long userId;
    private String deviceId;
    private String fingerprintHash;
    private String deviceName;
    private String status;
    private OffsetDateTime lastSeenAt;
}
