package com.superprogrammer.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {
    /** 显式模型；为空时由 LlmGateway 使用管理员配置的默认对话模型。 */
    private String model;
    private List<LlmMessage> messages;
    @Builder.Default
    private Double temperature = 0.7;
    /** 对话输出上限。4096 实证截断长文档回复（kimi 严格按 max_tokens 停、stop_reason=max_tokens），8192 覆盖常规长答。 */
    @Builder.Default
    private Integer maxTokens = 8192;
    /**
     * 关闭模型思考（Anthropic 协议 {@code thinking.type=disabled}）。
     * 内部 JSON 蒸馏类调用必开：思考 token 与正文共享 max_tokens 预算（2026-08-16 实证
     * kimi k3/glm-5.1 在 800 上限下思考吃满预算、JSON 全部截断→文件记忆 FAILED/记忆生成降级）。
     * kimi 尊重该参数（实测干净 JSON）；glm 当前忽略（无害，靠 maxTokens 余量兜底）。默认 false 不影响对话流。
     */
    @Builder.Default
    private Boolean disableThinking = false;
    /**
     * 思考强度档位（修复IX-1，Q1 三档）。可空且无默认值——null=不发思考参数（现状），
     * 优先级高于 {@link #disableThinking}（后者保留给记忆内部 JSON 蒸馏调用，零改动）。
     */
    private ThinkingLevel thinkingLevel;
    @Builder.Default
    private Boolean stream = false;
    /** 单次非流式请求的局部超时；null 表示使用 Provider 默认值。 */
    private Integer timeoutMs;
    /** RAG trace purpose; null uses the gateway's endpoint default. */
    private String callPurpose;
    /**
     * 归属 chat 会话 id（安全体系 S3 · SEC-FR-056 / LLM10 会话 token 上限）：
     * 透传计费落 llm_usage_logs.session_id（V122），发送前 SUM 检查在此列上做。
     * null=非会话调用（记忆后台/解析摘要/画布节点等），不参与会话封顶。
     */
    private String sessionId;

    /**
     * 项目组归属（计划5 Step4）：null=个人计费（现状）；有值→网关预检组池/成员身份，
     * 计费走 chargeGroup，usage 落 llm_usage_logs.project_group_id（账单事实源）。
     */
    private Long projectGroupId;
}
