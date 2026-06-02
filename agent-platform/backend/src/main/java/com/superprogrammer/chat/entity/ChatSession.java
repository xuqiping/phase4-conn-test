package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_sessions")
public class ChatSession extends BaseEntity {

    private Long userId;
    private String title;
    private Long agentId;
    private Long workflowId;
    private String mode;
    private String status;
    private String variables;
}
