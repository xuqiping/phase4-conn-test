package com.superprogrammer.chat.service.internal;

import java.time.OffsetDateTime;

/**
 * 计划12 · D · 召回时间窗（总体设计 §3.3 参数表：不限 / 相对 N 天 / 绝对时段）。
 * <p>
 * 三字段全 null = 不限（全时段召回）。三选一或组合：
 * <ul>
 *   <li>{@code relativeDays} —— 仅取近 N 天（now - N 天 至 now），优先于 start/end。</li>
 *   <li>{@code start}/{@code end} —— 绝对时段上下界，任一 null 表示该侧无界。</li>
 * </ul>
 * 实际 SQL 过滤在 D-5/D-6 取数 mapper 内联（turn.created_at 维度）。
 */
public record RecallTimeWindow(
        Integer relativeDays,
        OffsetDateTime start,
        OffsetDateTime end
) {
    /** 不限时间窗（全时段）。 */
    public static RecallTimeWindow unbounded() {
        return new RecallTimeWindow(null, null, null);
    }

    /** 三字段全 null = 不限。 */
    public boolean isUnbounded() {
        return relativeDays == null && start == null && end == null;
    }
}
