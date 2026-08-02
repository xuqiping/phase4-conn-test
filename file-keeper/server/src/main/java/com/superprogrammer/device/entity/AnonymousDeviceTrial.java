package com.superprogrammer.device.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("anonymous_device_trials")
public class AnonymousDeviceTrial extends BaseEntity {

    private String deviceId;
    private String fingerprintHash;
    private String deviceName;
    private OffsetDateTime trialStartedAt;
    private OffsetDateTime trialExpiresAt;
    private String freeModuleCode;
    private OffsetDateTime freeModuleSelectedAt;
    private OffsetDateTime lastFreeModuleChangedAt;
    private String status;
}
