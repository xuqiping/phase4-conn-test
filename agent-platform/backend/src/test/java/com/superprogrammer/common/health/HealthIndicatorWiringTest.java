package com.superprogrammer.common.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.system.DiskSpaceHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运维系统 OPS-FR-09：bean 装配红线验证（不依赖 DB，ApplicationContextRunner 纯上下文）。
 * 核心断言：自定义 diskSpaceHealthIndicator 顶掉 Boot 默认绝对阈值版
 * （Boot 3.2.5 DiskSpaceHealthContributorAutoConfiguration 已反编译确认 @ConditionalOnMissingBean(name=...) 让步）。
 */
class HealthIndicatorWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DiskSpaceHealthContributorAutoConfiguration.class))
            .withBean("diskSpaceHealthIndicator", DiskSpaceHealthIndicator.class, DiskSpaceHealthIndicator::new);

    // ---- 正向：自定义百分比版生效，容器中只此一例 ----

    @Test
    void customDiskSpaceIndicatorWinsOverBootDefault() {
        runner.run(context -> {
            assertTrue(context.containsBean("diskSpaceHealthIndicator"));
            HealthIndicator bean = (HealthIndicator) context.getBean("diskSpaceHealthIndicator");
            assertInstanceOf(DiskSpaceHealthIndicator.class, bean,
                    "diskSpaceHealthIndicator 必须是百分比阈值自定义版，而非 Boot 绝对阈值默认版");
            assertTrue(context.getBeansOfType(
                    org.springframework.boot.actuate.system.DiskSpaceHealthIndicator.class).isEmpty(),
                    "Boot 默认 DiskSpaceHealthIndicator 须被顶掉（ConditionalOnMissingBean 让步）");
        });
    }

    // ---- 反向：无自定义 bean 时 Boot 默认版兜底注册（证明让步逻辑本身在工作）----

    @Test
    void bootDefaultRegistersWhenNoCustomBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DiskSpaceHealthContributorAutoConfiguration.class))
                .run(context -> assertInstanceOf(
                        org.springframework.boot.actuate.system.DiskSpaceHealthIndicator.class,
                        context.getBean("diskSpaceHealthIndicator")));
    }
}
