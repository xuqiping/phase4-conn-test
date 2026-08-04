package com.superprogrammer.chat.service.internal;

import java.util.List;

/**
 * 计划12 · D · 召回 scope（用户多选参数容器，总体设计 §3.3 + §3.5 参数表）。
 * <p>
 * <b>非</b> legacy {@code MemoryScope}（基旧 {@code is_global}+{@code user_memory_projects}）——
 * 计划12 完全替换旧栈，scope 模型重建为「个人 on/off ∪ 项目多选 ∪ 方向 ∪ 时间窗 ∪ 离职开关」。
 * <p>
 * 召回取数时：
 * <ul>
 *   <li><b>个人 scope</b>：{@code user_id=self AND born_personal=true}（设计 §3.3 line 114，项目出身不进个人召回）。</li>
 *   <li><b>项目 scope</b>：{@code project_ids && X} 经 {@code readableAuthors} ACL（D-6 取数实装）。</li>
 * </ul>
 * 字段仅记开关，出身/ACL 过滤在取数 mapper 层（D-2/D-4/D-5）。
 *
 * @param includeDeparted L10「同步召回已离开人员」开关（默认 true）；<b>本迭代留字段不实装过滤，接入在 I3</b>。
 *                        优先级高于人员多选，同时控召回与总结取数。
 */
public record RecallScope(
        boolean personalOn,
        List<Long> projectIds,
        RecallDirection direction,
        RecallTimeWindow timeWindow,
        boolean includeDeparted
) {
    /** 防御性不可变 + null 兜底。 */
    public RecallScope {
        projectIds = projectIds == null ? List.of() : List.copyOf(projectIds);
        direction = direction == null ? RecallDirection.BOTH : direction;
        timeWindow = timeWindow == null ? RecallTimeWindow.unbounded() : timeWindow;
    }

    /** 默认 scope = 仅个人（首次无历史 / 入参 null 兜底，设计 §3.3 line 113）。 */
    public static RecallScope defaultPersonalOnly() {
        return new RecallScope(true, List.of(), RecallDirection.BOTH, RecallTimeWindow.unbounded(), true);
    }

    /** 空召回 scope（取消全部勾选，不报错返空，L2 边界）。 */
    public boolean isEmpty() {
        return !personalOn && projectIds.isEmpty();
    }

    /** 防御性非 null。 */
    public List<Long> safeProjectIds() {
        return projectIds;
    }
}
