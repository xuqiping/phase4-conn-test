package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 计划12 · D-6 · 召回主流程产物（总体设计 §3.3 ⑦装配 + 运维打点）。
 * <p>
 * <b>{@code assembledText}</b>：七步装配后的可直接注入对话 prompt 的记忆文本（按 subject 聚合打 owner 前缀，
 * subject='我' owner=self 省主体；owner≠self 打 {@code user#{id}} 占位，D-7 前端查用户名美化）。
 * <p>
 * <b>降级链</b>：子组件内部已消化 LLM 失败（selector/reader 不抛），Pipeline 层每步再 try-catch 兜意外异常，
 * 命中则 {@code degraded=true} + {@code notes} 收明细（设计 §3.3「降级」 + 运维「LLM 失败降级链」）。
 * <p>
 * <b>打点</b>：{@code steps} 每步耗时 + 条数 + ok 标志，{@code traceId} 串全流程日志。
 *
 * @param assembledText 装配文本（注入 prompt）
 * @param selectedTags  D-3 选中标签（展示/debug）
 * @param summaryCount  召回总结条数
 * @param turnCount     拼入未覆盖流水账条数
 * @param degraded      是否走过降级
 * @param notes         降级/告警明细（空表 = 全程正常）
 * @param traceId       本次召回 trace id
 * @param steps         每步打点
 */
@Data
@Builder
public class MemoryRecallResult {

    private String assembledText;
    private List<RecallTagMeta> selectedTags;
    private int summaryCount;
    private int turnCount;
    private boolean degraded;
    private List<String> notes;
    private String traceId;
    private List<RecallTraceStep> steps;
}
