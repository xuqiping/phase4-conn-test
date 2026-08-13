package com.superprogrammer.common.metrics;

import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运维系统 OPS-FR-03~07 骨架：BizMetrics 注册行为测试。
 * 用真 PrometheusMeterRegistry 验证 Prometheus 命名（llm.calls → llm_calls_total）与 tag 白名单。
 */
class BizMetricsTest {

    private PrometheusMeterRegistry registry;
    private BizMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new BizMetrics(registry);
    }

    private String scrape() {
        return registry.scrape();
    }

    @Test
    void llmCall_incrementsWithTags() {
        metrics.llmCall("glm", "glm-5.1", BizMetrics.RESULT_SUCCESS);
        metrics.llmCall("glm", "glm-5.1", BizMetrics.RESULT_SUCCESS);
        metrics.llmCall("glm", "glm-5.1", BizMetrics.RESULT_FAIL);
        String out = scrape();
        assertTrue(out.contains("llm_calls_total{model=\"glm-5.1\",provider=\"glm\",result=\"success\",} 2.0"), out);
        assertTrue(out.contains("llm_calls_total{model=\"glm-5.1\",provider=\"glm\",result=\"fail\",} 1.0"), out);
    }

    @Test
    void llmCall_nullTagBecomesUnknown_notThrow() {
        // 高基数红线兜底：null tag 不抛异常拖垮主链路，归一 unknown
        metrics.llmCall(null, "m", BizMetrics.RESULT_SUCCESS);
        assertTrue(scrape().contains("provider=\"unknown\""));
    }

    @Test
    void llmTokens_zeroNotRecorded() {
        metrics.llmTokens("glm", "glm-5.1", BizMetrics.DIRECTION_IN, 0);
        assertFalse(scrape().contains("llm_tokens_total"));
        metrics.llmTokens("glm", "glm-5.1", BizMetrics.DIRECTION_IN, 42);
        assertTrue(scrape().contains("llm_tokens_total{direction=\"in\",model=\"glm-5.1\",provider=\"glm\",} 42.0"));
    }

    @Test
    void llmLatency_recordsHistogram() {
        metrics.llmLatency("glm", "glm-5.1", Duration.ofMillis(800));
        assertTrue(scrape().contains("llm_latency_seconds_count{model=\"glm-5.1\",provider=\"glm\",} 1.0"));
    }

    @Test
    void workflowMetrics_recorded() {
        metrics.workflowExecution("SUCCESS");
        metrics.workflowExecution("FAILED");
        metrics.workflowDuration(Duration.ofSeconds(3));
        String out = scrape();
        assertTrue(out.contains("workflow_executions_total{status=\"SUCCESS\",} 1.0"), out);
        assertTrue(out.contains("workflow_executions_total{status=\"FAILED\",} 1.0"), out);
        assertTrue(out.contains("workflow_duration_seconds_count 1.0"), out);
    }

    @Test
    void indexQueueDepth_gaugeReadsSupplier_andRegisteredOnce() {
        AtomicLong depth = new AtomicLong(7);
        metrics.registerIndexQueueDepth(depth::get);
        // 重复注册静默忽略（不覆盖回调、不抛错）
        metrics.registerIndexQueueDepth(() -> 999);
        assertTrue(scrape().contains("knowledge_index_queue_depth 7.0"));
        depth.set(3);
        assertTrue(scrape().contains("knowledge_index_queue_depth 3.0"));
        assertFalse(scrape().contains("999"));
    }

    @Test
    void indexQueueDepth_nullSupplierValue_readsZero() {
        metrics.registerIndexQueueDepth(() -> null);
        assertTrue(scrape().contains("knowledge_index_queue_depth 0.0"));
    }

    @Test
    void indexed_resultVariants() {
        metrics.indexed(BizMetrics.INDEX_SUCCESS);
        metrics.indexed(BizMetrics.INDEX_FAIL);
        metrics.indexed(BizMetrics.INDEX_VOID);
        String out = scrape();
        assertTrue(out.contains("knowledge_indexed_total{result=\"success\",} 1.0"));
        assertTrue(out.contains("knowledge_indexed_total{result=\"fail\",} 1.0"));
        assertTrue(out.contains("knowledge_indexed_total{result=\"void\",} 1.0"));
    }

    @Test
    void knowledgeChunkMetricsUseOnlyBoundedGranularityTags() {
        metrics.knowledgeChunked("S1", 2);
        metrics.knowledgeChunked("C2", 5);
        metrics.knowledgeChunkDuration(Duration.ofMillis(120));

        String out = scrape();
        assertTrue(out.contains("knowledge_chunks_total{granularity=\"S1\",} 2.0"), out);
        assertTrue(out.contains("knowledge_chunks_total{granularity=\"C2\",} 5.0"), out);
        assertTrue(out.contains("knowledge_chunk_duration_seconds_count 1.0"), out);
    }

    @Test
    void memoryMetrics_recorded() {
        metrics.memoryPipelineDuration(Duration.ofMillis(500));
        metrics.memoryIncident();
        String out = scrape();
        assertTrue(out.contains("memory_pipeline_duration_seconds_count 1.0"));
        assertTrue(out.contains("memory_incidents_total 1.0"));
    }

    @Test
    void authMetrics_recorded() {
        metrics.authLogin(BizMetrics.RESULT_SUCCESS);
        metrics.authLogin(BizMetrics.RESULT_FAIL);
        metrics.registerRateLimited();
        String out = scrape();
        assertTrue(out.contains("auth_login_total{result=\"success\",} 1.0"));
        assertTrue(out.contains("auth_login_total{result=\"fail\",} 1.0"));
        assertTrue(out.contains("auth_register_rate_limited_total 1.0"));
    }

    @Test
    void metricsBucketConfig_appliesLlmLatencySla() {
        MetricsBucketConfig cfg = new MetricsBucketConfig();
        cfg.bizMetricsBuckets().customize(registry);
        metrics.llmLatency("p", "m", Duration.ofMillis(800));
        String out = scrape();
        // SLO bucket 生效：800ms 落 1s 桶
        assertTrue(out.contains("llm_latency_seconds_bucket{model=\"m\",provider=\"p\",le=\"1.0\",} 1.0"), out);
        assertTrue(out.contains("llm_latency_seconds_bucket{model=\"m\",provider=\"p\",le=\"0.5\",} 0.0"), out);
    }
}
