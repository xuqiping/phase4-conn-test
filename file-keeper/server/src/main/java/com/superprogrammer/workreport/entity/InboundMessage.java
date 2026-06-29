package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inbound_messages")
public class InboundMessage extends BaseEntity {

    private Long userId;
    private String platform;
    private String platformMessageId;
    private String senderId;
    private String senderName;
    private String rawText;
    private String intent;
    private BigDecimal confidence;
    private String parsedPayload;
    private String status;
    private String targetModule;
    private Long targetId;
}
