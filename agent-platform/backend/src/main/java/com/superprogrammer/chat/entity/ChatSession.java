package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.LongArrayTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "chat_sessions", autoResultMap = true)
public class ChatSession extends BaseEntity {

    private Long userId;
    private String title;
    private Long agentId;
    private Long workflowId;
    private String mode;
    private String status;
    private String variables;

    /** CHAT 模式检索 scope（V25 BIGINT[]，阶段5 RAG 绑定）。 */
    @TableField(typeHandler = LongArrayTypeHandler.class)
    private List<Long> kbIds;

    /** 记忆模式开关（V26，null=继承 agent/workflow/global）。 */
    private Boolean ragEnabled;
}
