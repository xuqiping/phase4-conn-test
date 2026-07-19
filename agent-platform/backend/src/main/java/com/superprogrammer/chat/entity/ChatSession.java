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

    /** 项目记忆写目标（V33，新事实落这，null=总记忆会话）。 */
    private Long projectId;

    /** 读开关：是否注入总记忆（V33，默认 true）。 */
    private Boolean memIncludeGlobal;

    /** 读开关：开启读取的项目 id 集合（V33，扁平对称开关集，经权限过滤后用）。 */
    @TableField(typeHandler = LongArrayTypeHandler.class)
    private List<Long> memReadProjectIds;

    /** 联网搜索开关（V44，CHAT 模式会话级持久化；null=继承默认关）。ON→生成前联网检索注入。 */
    private Boolean webSearchEnabled;
}
