package com.superprogrammer.llm.dto;

/**
 * 思考强度档位（修复IX-1，Q1 拍板三档）。
 * <p>语义：OFF=明确关思考；STANDARD=开+中预算；DEEP=开+大预算。
 * Provider 消费优先级：{@code thinkingLevel != null} 用之；否则 {@code disableThinking=true}→OFF；
 * 否则不发任何思考参数（现状，模型默认行为）——老前端/未选档零影响。
 * <ul>
 *   <li>Anthropic 协议：disabled / enabled+budget（预算见 LlmThinkingProperties）。</li>
 *   <li>OpenAI 兼容系按 provider config 声明映射：toggle 风格仅认 OFF/STANDARD（协议无深度态，
 *       DEEP 不下发该风格）；effort 风格 reasoning_effort low/medium/high。未声明=零参数。</li>
 * </ul>
 */
public enum ThinkingLevel {
    OFF,
    STANDARD,
    DEEP
}
