package com.superprogrammer.common.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运维系统 OPS-FR-09：磁盘百分比阈值健康检查单测（纯函数构造 total/free，不依赖真实磁盘）。
 */
class DiskSpaceHealthIndicatorTest {

    private static final File PATH = new File(".");

    // ---- 正向：可用 20%（>15% 阈值）→ UP ----

    @Test
    void upWhenFreeAboveThreshold() {
        Health health = DiskSpaceHealthIndicator.buildHealth(PATH, 1000L, 200L, 0.15);

        assertEquals(Status.UP, health.getStatus());
        assertEquals(20.0, (Double) health.getDetails().get("freePercent"), 0.01);
    }

    // ---- 边界：恰好 15% → UP（红线是「<15% 报 DOWN」）----

    @Test
    void upWhenFreeExactlyAtThreshold() {
        Health health = DiskSpaceHealthIndicator.buildHealth(PATH, 1000L, 150L, 0.15);

        assertEquals(Status.UP, health.getStatus());
    }

    // ---- 反向：可用 14%（<15%）→ DOWN ----

    @Test
    void downWhenFreeBelowThreshold() {
        Health health = DiskSpaceHealthIndicator.buildHealth(PATH, 1000L, 140L, 0.15);

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(15.0, (Double) health.getDetails().get("thresholdPercent"), 0.01);
    }

    // ---- 异常：读不到总量（total=0）→ DOWN 且带原因 ----

    @Test
    void downWhenTotalUnreadable() {
        Health health = DiskSpaceHealthIndicator.buildHealth(PATH, 0L, 0L, 0.15);

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("无法读取磁盘总量", health.getDetails().get("reason"));
    }

    // ---- 真实磁盘冒烟：health() 返回明细齐全 ----

    @Test
    void realDiskSmoke() {
        Health health = new DiskSpaceHealthIndicator().health();

        assertNotNull(health.getStatus());
        assertTrue(health.getDetails().containsKey("path"));
        assertTrue((Long) health.getDetails().get("total") > 0);
    }
}
