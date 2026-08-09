package com.superprogrammer.common.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;

/**
 * 业务指标直方图 bucket 配置（运维系统 OPS-FR-03/04/06）。
 * llm.latency 用毫秒~分钟级 bucket（LLM 慢调用量级）；workflow/memory 管线同量级。
 * 开 percentileHistogram 后 Prometheus 暴露 _bucket 序列，Grafana 可算 P95/P99。
 */
@Configuration
public class MetricsBucketConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> bizMetricsBuckets() {
        return registry -> registry.config().meterFilter(
                new MeterFilter() {
                    @Override
                    public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                        if (id.getName().equals("llm.latency")) {
                            return DistributionStatisticConfig.builder()
                                    .serviceLevelObjectives(
                                            Duration.ofMillis(100).toNanos(),
                                            Duration.ofMillis(500).toNanos(),
                                            Duration.ofSeconds(1).toNanos(),
                                            Duration.ofSeconds(2).toNanos(),
                                            Duration.ofSeconds(5).toNanos(),
                                            Duration.ofSeconds(10).toNanos(),
                                            Duration.ofSeconds(30).toNanos(),
                                            Duration.ofSeconds(60).toNanos())
                                    .build()
                                    .merge(config);
                        }
                        if (id.getName().equals("workflow.duration") || id.getName().equals("memory.pipeline.duration")) {
                            return DistributionStatisticConfig.builder()
                                    .percentilesHistogram(true)
                                    .build()
                                    .merge(config);
                        }
                        return config;
                    }
                });
    }
}
