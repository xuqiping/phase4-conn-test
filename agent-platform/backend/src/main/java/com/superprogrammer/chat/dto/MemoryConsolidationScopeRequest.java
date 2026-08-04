package com.superprogrammer.chat.dto;

import lombok.Data;

import java.util.List;

/**
 * 计划12 · E · 单个总结 scope 的取数配置（总体设计 §3.4「项目总结取数范围可配」）。
 * <p>
 * 一个总结任务 = 一个 scope（PERSONAL 或 某 PROJECT）+ 该 scope 的取数范围。
 * 手动总结可一次提交多个 scope（{@link MemoryConsolidationTriggerRequest}），每个 scope 独立取数压缩。
 * <p>
 * <b>取数范围</b>（设计 §3.4 line 127-130）：
 * <ul>
 *   <li>人员：SELF（仅自己，默认）/ SPECIFIC（项目内某几人，{@code authorIds}）/ ALL（全部可召回人员）；</li>
 *   <li>方向：INPUT/OUTPUT/BOTH（默认 BOTH）；</li>
 *   <li>产出 summary 恒 {@code user_id=作者本人}，被选人员的 turn 喂进作者 summary。</li>
 * </ul>
 * <p>
 * <b>越权防护</b>：PROJECT 须 {@code projectId} 经「本人可访问项目集」过滤；SPECIFIC 的
 * {@code authorIds} 须 ∩ readableAuthors（I1），由 service 层校验（向量 14）。
 */
@Data
public class MemoryConsolidationScopeRequest {

    /** PERSONAL / PROJECT。null → 视 PERSONAL。 */
    private String scopeKind;

    /** PROJECT 时的项目 id；PERSONAL 忽略。须本人可访问（service 校验）。 */
    private Long projectId;

    /** 人员范围：SELF（默认）/ SPECIFIC / ALL。
     *  SELF 仅总结自己 turn；SPECIFIC 取 {@code authorIds}；ALL = readableAuthors ∩ 离职开关过滤后集。 */
    private String authorFilter;

    /** SPECIFIC 时的人员集；须 ∩ readableAuthors（向量 14 防越权读他人）。 */
    private List<Long> authorIds;

    /** 方向 INPUT/OUTPUT/BOTH，null/非法 → BOTH。 */
    private String direction;

    /** L10「同步已离开人员」开关（§3.7 line158，同时控总结取数），null → 默认 true（不过滤）。
     *  false → 项目候选剔 DEPARTED（优先级高于人员多选，即便 SPECIFIC 勾了离职人员也剔）。 */
    private Boolean includeDeparted;
}
