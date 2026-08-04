package com.superprogrammer.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 计划12 · E · 总结时序冲突裁决请求（总体设计 §3.5 四选项 + §3.8 DISCARD 级联）。
 * <p>
 * <b>偏离 plan</b>：plan 列「改 {@code MemoryConflictResolveRequest}」——后者是 legacy
 * {@code user_memories} 单值冲突 DTO（decision+customValue，KEEP_CUSTOM 自定义合并）。
 * 新模型冲突只来自总结时序互斥（无 type 列、无 customValue、无 KEEP_CUSTOM），关联 tag+summary。
 * 故新建本 DTO 避免新旧语义纠缠（承 C/D 隔离裁决）。
 * <p>
 * <b>四选项</b>（设计 §3.5 line 141）：
 * <ul>
 *   <li>{@code KEEP_BOTH} —— 两条都留，按 {@code created_at} 自动排序（无需用户填日期）；</li>
 *   <li>{@code KEEP_NEW} / {@code KEEP_OLD} —— 留一条，败方 summary 软删（turns 不动）；</li>
 *   <li>{@code DISCARD} —— 软删冲突 summary + 其 {@code source_turn_ids} 全部 turns，
 *       走 §3.8 级联（12h 拒 + 他人引用 STALE + 重压缩 + 波及通知）。</li>
 * </ul>
 * <p>
 * <b>非作者不可裁决</b>（向量 6 + 15）：service 校验 conflict.user_id == 登录 uid。
 */
@Data
public class MemorySummaryConflictResolveRequest {

    /** KEEP_BOTH / KEEP_NEW / KEEP_OLD / DISCARD。 */
    @NotBlank
    private String decision;
}
