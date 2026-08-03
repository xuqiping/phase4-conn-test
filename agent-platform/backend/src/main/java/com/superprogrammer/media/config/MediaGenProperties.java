package com.superprogrammer.media.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 媒体生成配置（运维开关 + 上限，spec §4 性能/安全）。
 *
 * <p>用法：{@code media.gen-enabled}(默认 true) 总开关；{@code media.max-duration}(默认 10 秒)、
 * {@code media.max-res}(默认 720p) 控盘与成本上限；{@code media.poll-ms}(默认 5000) worker 轮询间隔；
 * {@code media.lock-minutes}(默认 5) 认领锁时长；{@code media.task-timeout-seconds}(默认 600) 单任务最长等待。
 */
@Data
@Component
@ConfigurationProperties(prefix = "media")
public class MediaGenProperties {

    /** 总开关。false 时 submit 直接拒绝（功能降级）。 */
    private boolean genEnabled = true;

    /** 时长上限（秒）。 */
    private int maxDuration = 10;

    /** 分辨率上限（白名单 + 上限双重校验）。 */
    private String maxRes = "720p";

    /** worker 轮询间隔（ms）。 */
    private long pollMs = 5000;

    /** 认领锁时长（分钟），过期后 RUNNING 行可被重新认领（崩溃恢复）。 */
    private int lockMinutes = 5;

    /** 单任务最长等待（秒），超时置 FAILED。 */
    private long taskTimeoutSeconds = 600;

    /** 退避轮询起始间隔（ms）。 */
    private long backoffStartMs = 5000;

    /** 退避轮询封顶间隔（ms）。 */
    private long backoffCapMs = 30000;
}
