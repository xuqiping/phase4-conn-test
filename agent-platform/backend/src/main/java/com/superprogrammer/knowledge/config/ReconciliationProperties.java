package com.superprogrammer.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * rag.reconciliation 配置（阶段7 对账 + decay 清理 worker，opt-in 默认关）。
 * 镜像 {@link AnswerCacheProperties} / {@link VisibilityCacheProperties} 风格。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.reconciliation")
public class ReconciliationProperties {

    /** 总开关（默认关，与 answer-cache/memory 同 opt-in 哲学）。 */
    private boolean enabled = false;

    /** 轮询间隔毫秒（默认 10 分钟）。 */
    private long pollMs = 600_000L;

    /** answer_cache decay 清理每批删除上限。 */
    private int decayBatch = 500;

    /** 每 KB 扫描批大小（listActiveKbIds LIMIT）。 */
    private int kbBatch = 20;

    /**
     * 自动修复（drift → 入 REINDEX job，claimBatch 现消费 REINDEX，worker 重嵌修复 drift）。
     * 启用即承担 drift node 的 re-embed LLM 计费。默认 false（report-only + decay/orphan purge），
     * 与 enabled 同 opt-in 哲学——确认计费影响后再 yml 翻 true。
     */
    private boolean autoRepair = false;
}
