package com.superprogrammer.chat.dto;

import jakarta.validation.Valid;
import lombok.Data;

import java.util.List;

/**
 * 计划12 · E · 手动总结触发请求（总体设计 §3.4「统一入口 · 手动总结」）。
 * <p>
 * 「开始总结」入口（设置页/总结页签/告警按钮）弹框列 {个人} ∪ {本人 ACTIVE 项目}，
 * 每个 scope 标可选/灰选（{@link MemoryConsolidationTargetView}），勾选确认后提交本请求。
 * <p>
 * 手动总结<b>独立于任何 gen/总结开关</b>（设计 §3.4 line 121），且<b>手动才 backfill raw</b>
 * （scope 内 {@code gen_done=false} 的 raw turn 先补 tag 再压缩，分批 ≤20/批）。
 * 定时路径不 backfill raw、不调生成 LLM（gen 关态空跳过）。
 * <p>
 * <b>幂等</b>：手动触发也走 consolidation_scopes 行锁（auto_enabled=false），与定时 worker 互斥不双跑。
 */
@Data
public class MemoryConsolidationTriggerRequest {

    /** 勾选的 scope 取数配置列表（每个独立取数压缩）。至少 1 个。 */
    @Valid
    private List<MemoryConsolidationScopeRequest> scopes;
}
