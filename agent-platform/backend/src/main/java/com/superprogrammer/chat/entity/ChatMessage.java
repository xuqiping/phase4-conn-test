package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "chat_messages", autoResultMap = true)
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;
    private String role;
    private String content;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String metadata;
    private OffsetDateTime createdAt;
}
