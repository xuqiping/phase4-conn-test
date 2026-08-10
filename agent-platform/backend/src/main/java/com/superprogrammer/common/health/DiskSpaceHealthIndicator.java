package com.superprogrammer.common.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 运维系统 OPS-FR-09：磁盘健康检查改百分比阈值——可用空间 &lt;15% 报 DOWN（对齐磁盘告警线）。
 * Boot 默认是绝对阈值（10MB），对大盘几乎不触发、对小盘又太苛刻；百分比与告警规则同口径。
 * bean 名取 {@code diskSpaceHealthIndicator}：Boot 自动配置 @ConditionalOnMissingBean(name=...) 自动让步。
 */
@Component("diskSpaceHealthIndicator")
public class DiskSpaceHealthIndicator implements HealthIndicator {

    static final double DEFAULT_FREE_THRESHOLD_RATIO = 0.15;

    private final File path;
    private final double freeThresholdRatio;

    public DiskSpaceHealthIndicator() {
        this(new File("."), DEFAULT_FREE_THRESHOLD_RATIO);
    }

    /** 测试可注入路径与阈值。 */
    DiskSpaceHealthIndicator(File path, double freeThresholdRatio) {
        this.path = path;
        this.freeThresholdRatio = freeThresholdRatio;
    }

    @Override
    public Health health() {
        return buildHealth(path, path.getTotalSpace(), path.getUsableSpace(), freeThresholdRatio);
    }

    /** 纯函数便于单测：total/free 任意构造。 */
    static Health buildHealth(File path, long totalBytes, long freeBytes, double freeThresholdRatio) {
        long thresholdBytes = (long) (totalBytes * freeThresholdRatio);
        Health.Builder builder = (totalBytes > 0 && freeBytes >= thresholdBytes ? Health.up() : Health.down())
                .withDetail("path", path.getAbsolutePath())
                .withDetail("total", totalBytes)
                .withDetail("free", freeBytes)
                .withDetail("freePercent", totalBytes > 0 ? Math.round(freeBytes * 1000.0 / totalBytes) / 10.0 : 0.0)
                .withDetail("thresholdPercent", freeThresholdRatio * 100);
        if (totalBytes <= 0) {
            builder.withDetail("reason", "无法读取磁盘总量");
        }
        return builder.build();
    }
}
