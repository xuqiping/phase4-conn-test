package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 计划12 · E · 总结入口弹框的 scope 可选/灰选项（总体设计 §3.4「手动总结」+ §3.9 告警）。
 * <p>
 * 列用户参与的全部 scope = {个人} ∪ {本人 ACTIVE 项目}（已离开项目不可选，设计 §3.4 line 119）。
 * 每个 scope 标：
 * <ul>
 *   <li>{@code hasChange} —— 该 scope 距上次总结后是否有变化（新增 {@code gen_done=true} 且无 coverage 行的 turn，
 *       或 {@code gen_done=false} 的 raw turn）。true=可选、false=灰选（仍可强制跑，独立于开关）；</li>
 *   <li>{@code uncoveredCount} —— 未总结 turn 计数（{@code gen_done=true} 且无 coverage(user_id=self) 行；
 *       raw 不计入告警阈值，设计 §3.9 line 178），>100 触发告警 badge；</li>
 *   <li>{@code autoEnabled} —— 是否已加入自动定时总结（{@code memory_consolidation_scopes.auto_enabled}）。</li>
 * </ul>
 */
@Data
@Builder
public class MemoryConsolidationTargetView {

    /** PERSONAL / PROJECT。 */
    private String scopeKind;

    /** PROJECT 时的项目 id；PERSONAL=null。 */
    private Long projectId;

    /** 展示名（个人 / 项目名）。 */
    private String displayName;

    /** 有无变化（可选/灰选判据）。 */
    private boolean hasChange;

    /** 未总结 turn 计数（gen_done=true 且无 coverage；raw 不计）。 */
    private int uncoveredCount;

    /** 是否已加入自动定时总结。 */
    private boolean autoEnabled;
}
