package com.superprogrammer.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 思考强度参数（修复IX-1，Q1/Q2 拍板）。application.yml: llm.thinking.{budget-standard,budget-deep,hold-factor-standard,hold-factor-deep}。
 *
 * <p>预算用于 Anthropic 协议 {@code thinking.budget_tokens}（下限 1024，配置低于此值发送时 clamp）；
 * 系数用于 HOLD 预扣出量估算放大（{@code LlmBillingService.holdChat}）——深度思考输出 token 实际可达数千，
 * 不放大会结算远超预扣频繁挂 DEBT（Q2）。全部带默认值，零配置可用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm.thinking")
public class LlmThinkingProperties {

    /** STANDARD 档思考预算（Anthropic budget_tokens）。 */
    private int budgetStandard = 2048;

    /** DEEP 档思考预算（Anthropic budget_tokens）。 */
    private int budgetDeep = 8192;

    /** STANDARD 档 HOLD 预扣出量估算放大系数（2048×2=4096）。 */
    private int holdFactorStandard = 2;

    /** DEEP 档 HOLD 预扣出量估算放大系数（2048×4=8192）。 */
    private int holdFactorDeep = 4;
}
