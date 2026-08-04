package com.superprogrammer.chat.dto;

/**
 * 计划12 · D-6 · 召回单步打点（总体设计 §3.3 运维「7 步每步打点含 traceId + 耗时」）。
 * <p>
 * 不可变；{@code count} = 该步产物条数（标签/总结/turns）；{@code ok=false} = 该步异常走降级。
 *
 * @param step        步名（resolve/aggregate/select/read/patch/assemble）
 * @param durationMs  耗时（毫秒）
 * @param count       产物条数（无产物步取 0）
 * @param ok          是否正常（false = 降级）
 */
public record RecallTraceStep(String step, long durationMs, int count, boolean ok) {
}
