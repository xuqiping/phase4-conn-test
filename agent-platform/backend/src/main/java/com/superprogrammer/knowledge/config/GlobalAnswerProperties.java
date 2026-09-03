package com.superprogrammer.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * C7 全局问答分支旋钮（WP4 Step2，prefix {@code rag.global.answer}）。
 * kill switch：enabled=false 时 GLOBAL 分类照常标记，但走常规检索管道（不调 map-reduce）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.global.answer")
public class GlobalAnswerProperties {

    /** 总开关：false=GLOBAL 问题回落常规 chunk 检索（零 map-reduce LLM 调用）。 */
    private boolean enabled = true;

    /** map 每批文档 L1 数上限（对齐规格 §9.2 每批 ≤15）。 */
    private int batchSize = 15;

    /** 全局回答总预算（ms）：map+reduce 合计超时 → 降级「仅 L-KB 概览+提示缩小范围」。 */
    private int timeoutMs = 30000;

    /** map 输出 max_tokens（每批要点浓缩）。 */
    private int mapMaxTokens = 2000;

    /** reduce 输出 max_tokens（合成答案 ≤800 字，给足 3000 防烂尾）。 */
    private int reduceMaxTokens = 3000;

    /** 显式回答模型；null=网关默认对话模型。 */
    private String model;
}
