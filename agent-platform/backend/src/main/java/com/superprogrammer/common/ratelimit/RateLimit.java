package com.superprogrammer.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 通用限流注解（11x 加固 · P1-C2）：贴在 Controller 方法上即享限流。
 *
 * <p><b>阈值热更</b>：{@link #max()} 是代码默认值，运行期可被 system_settings
 * 键 {@code security.rate.<action>.max} 覆盖（后台热调不重启）。
 * 总闸：system_settings 键 {@code security.rate.enabled}=false 时全部放行。</p>
 *
 * <p><b>降级</b>：Redis 故障 → 放行（可用性 > 限流，与登录域 SEC-FR-001 一致）。</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 限流动作标识。用于：Redis key 后缀、指标 tag（有界枚举，安全）、
     * system_settings 覆盖键 {@code security.rate.<action>.max}。
     */
    String action();

    /** 窗口内最大次数（代码默认值，可被 system_settings 热覆盖）。 */
    int max();

    /** 窗口秒数。 */
    int windowSeconds();

    /** 限流算法。FIXED=固定窗口（INCR+EXPIRE，省资源）；SLIDING=滑动窗口（ZSET，更公平）。 */
    RateLimitAlgo algo() default RateLimitAlgo.FIXED;

    /** 限流维度。USER=优先 userId（未登录回落 IP）；IP=恒按客户端 IP。 */
    RateLimitScope scope() default RateLimitScope.USER;

    /** 限流算法枚举。 */
    enum RateLimitAlgo { FIXED, SLIDING }

    /** 限流维度枚举。 */
    enum RateLimitScope { USER, IP }
}
