package com.superprogrammer.chat.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 对话请求（建会话/发消息/流式共用）。
 * 安全体系 S3 · C4：入参上限收紧——message 8k（超长截 LLM 白烧 token 且绕不开会话封顶）、
 * 附件 ≤10（服务端归属校验前先挡量）、kbIds ≤20、model ≤100（防无界串直落 usage/日志）。
 * 均为软约束（可空字段不受影响），不影响既有调用方。
 */
@Data
public class ChatRequest {

    @Positive
    private Long sessionId;

    @Size(max = 8000, message = "消息长度不能超过8000字符")
    private String message;

    @Positive
    private Long agentId;

    @Positive
    private Long workflowId;

    @Size(max = 100)
    private String model;

    /** CHAT 模式检索 scope（阶段5 RAG）。 */
    @Size(max = 20)
    private List<Long> kbIds;

    /** 记忆模式开关（V26，非 null 时持久化到会话；null=不改）。 */
    private Boolean ragEnabled;

    /** 联网搜索开关（CHAT 模式，非 null 时持久化到会话；null=不改继承）。ON→LLM 生成前联网检索注入。 */
    private Boolean webSearchEnabled;

    /**
     * 思考强度档位（修复IX-1，Q1 三档）：OFF=关 / STANDARD=标准 / DEEP=深度。
     * null=不发思考参数（现状，模型默认）；白名单防任意串透传。
     */
    @Pattern(regexp = "OFF|STANDARD|DEEP", message = "思考强度仅支持 OFF/STANDARD/DEEP")
    private String thinkingLevel;

    /** 聊天附件 file_id 集（V69 二期 P3，须为本人的 CHAT 上传）。 */
    @Size(max = 10, message = "附件数量不能超过10个")
    private List<String> attachmentFileIds;

    /** 项目组归属（计划5 Step4）：null=个人计费；有值须为本人所在组，组池扣费。 */
    private Long projectGroupId;
}
