package com.superprogrammer.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM QueryPlanner 旋钮（WP2 Step4，prefix {@code rag.queryplanner}）。
 * 默认关——规则版 QueryPlanner 行为即基线；开启后 LLM 规划失败/超时/解析异常一律回退规则版。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.queryplanner")
public class LlmQueryPlannerProperties {

    private Llm llm = new Llm();

    @Data
    public static class Llm {
        /** 总开关：false=零 LLM 调用纯规则（默认）。 */
        private boolean enabled = false;
        /** 规划超时（CompletableFuture.orTimeout 守卫；LlmRequest.timeoutMs 同值双保险）。 */
        private int timeoutMs = 2000;
        /** 规划输出 max_tokens（结构化 JSON 小输出）。 */
        private int maxTokens = 512;
        /** 显式规划模型；null=网关默认对话模型。 */
        private String model;
    }
}
