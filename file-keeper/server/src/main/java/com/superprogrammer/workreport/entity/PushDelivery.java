package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("push_deliveries")
public class PushDelivery extends BaseEntity {

    private Long reportId;

    private Long targetId;

    private String status;

    private String response;

    private Integer triedCount;
}
