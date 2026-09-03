package com.superprogrammer.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 上下文嵌入旋钮（WP3 Step1，prefix {@code rag.contextual}）。
 * 默认开——新文档索引管线走 LLM 定位表；关=纯规则前缀现状（kill switch，误伤时 yml 关掉即回基线）。
 * 存量文档不自动重嵌（contextHash 只对新生成 job 生效），需 owner 显式重建（Step3 入口）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.contextual")
public class RagContextualProperties {

    private Llm llm = new Llm();

    @Data
    public static class Llm {
        /** 总开关：false=零 LLM 调用纯规则前缀。 */
        private boolean enabled = true;
        /** 定位表输出 max_tokens（坑点预判：20 chunk×50 字 ≤1500，给足 2000 防烂尾）。 */
        private int maxTokens = 2000;
        /** 单次调用 chunk 清单上限（防 prompt 无界；超出部分该 chunk 降级纯规则）。 */
        private int maxChunks = 60;
        /** 显式定位模型；null=网关默认对话模型。 */
        private String model;
    }
}
