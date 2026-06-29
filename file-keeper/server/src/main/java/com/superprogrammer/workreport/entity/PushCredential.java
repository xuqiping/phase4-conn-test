package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("push_credentials")
public class PushCredential extends BaseEntity {

    private Long userId;

    private String name;

    private String platform;

    private String credentialEnc;
}
