package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("push_targets")
public class PushTarget extends BaseEntity {

    private Long userId;

    private String name;

    private String platform;

    private String targetType;

    private String targetId;

    private Long credentialId;
}
