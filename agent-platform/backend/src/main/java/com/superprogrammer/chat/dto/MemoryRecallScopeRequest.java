package com.superprogrammer.chat.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 计划12 · D · 召回 scope 勾选提交（用户多选，总体设计 §3.3）。
 * <p>
 * 全字段可 null —— {@code null} 字段在 {@code MemoryRecallScopeResolver} 内兜底默认值，
 * 支持前端只传改动项。首次无历史默认 {@code {个人}}（设计 line 113）。
 * <p>
 * <b>scope 持久化</b>（保留上次选择，新会话沿用）在 D-7 controller 层做，本 DTO 仅描述本次勾选。
 */
@Data
public class MemoryRecallScopeRequest {
    /** 个人 scope 开关，null → 默认 true。 */
    private Boolean personalOn;
    /** 项目 scope 多选（并集）；不可访问项目由 resolver 滤掉防越权。 */
    private List<Long> projectIds;
    /** 召回方向 INPUT/OUTPUT/BOTH，null/非法 → BOTH。 */
    private String direction;
    /** 相对时间窗（近 N 天），优先于 start/end。null = 不用相对窗。 */
    private Integer relativeDays;
    /** 绝对时间窗下界，null = 无下界。 */
    private OffsetDateTime start;
    /** 绝对时间窗上界，null = 无上界。 */
    private OffsetDateTime end;
    /** L10「同步召回已离开人员」开关，null → 默认 true（本迭代留字段，过滤接入 I3）。 */
    private Boolean includeDeparted;
}
