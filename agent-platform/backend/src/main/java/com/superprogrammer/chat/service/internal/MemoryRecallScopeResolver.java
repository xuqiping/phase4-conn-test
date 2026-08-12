package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryRecallScopeRequest;
import com.superprogrammer.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划12 · D · 召回 scope 解析器（总体设计 §3.3）。
 * <p>
 * 把用户勾选（{@link MemoryRecallScopeRequest}）解析为 {@link RecallScope}：
 * <ol>
 *   <li>全 null 字段兜底默认值（personalOn=true / direction=BOTH / timeWindow=不限 / includeDeparted=true）。</li>
 *   <li>{@code projectIds} 经 {@link ProjectService#listAccessibleProjectIds} 过滤——
 *       防勾选被撤权的项目致越权读（设计 §6 向量 2：项目成员交集）。</li>
 *   <li>入参 null/空 → 默认 {@link RecallScope#defaultPersonalOnly()}（首次无历史默认 {个人}）。</li>
 * </ol>
 * <p>
 * <b>非</b> legacy {@code MemoryScopeResolver}（基旧 ChatSession 三列 + is_global）——
 * 新模型 scope 不绑 ChatSession，由用户显式多选请求驱动。
 * <p>
 * <b>出身判定</b>（个人 scope 召回按 {@code born_personal=true}）在 D-5/D-6 取数 mapper 实装，不在本 resolver。
 *
 * @see ProjectService#listAccessibleProjectIds 项目可访问性出口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRecallScopeResolver {

    private final ProjectService projectService;
    private final MemoryProjectUserGrantService grantService;

    /**
     * 解析用户勾选为召回 scope。
     *
     * @param req    用户勾选（可 null → 默认 {个人}）
     * @param userId 召回者 user id（null → 默认 {个人}，projectIds 过滤无意义）
     * @return 不可变 {@link RecallScope}
     */
    public RecallScope resolve(MemoryRecallScopeRequest req, Long userId) {
        // 入参 null → 首次无历史兜底默认 {个人}（设计 §3.3 line 113）
        if (req == null) {
            return RecallScope.defaultPersonalOnly();
        }

        boolean personalOn = req.getPersonalOn() == null || req.getPersonalOn();
        boolean includeDeparted = req.getIncludeDeparted() == null || req.getIncludeDeparted();
        RecallDirection direction = RecallDirection.fromString(req.getDirection());
        RecallTimeWindow timeWindow = new RecallTimeWindow(req.getRelativeDays(), req.getStart(), req.getEnd());

        // 项目经 listAccessibleProjectIds 过滤——防勾选被撤权项目致越权读（向量 2：项目成员交集）
        List<Long> rawProjects = req.getProjectIds() == null ? List.of() : req.getProjectIds();
        List<Long> projects = filterAccessible(rawProjects, userId);

        if (!projects.isEmpty()) {
            log.debug("recallScope userId={} personalOn={} projects={}（{} 项勾选，{} 项可访问）",
                    userId, personalOn, projects, rawProjects.size(), projects.size());
        }
        return new RecallScope(personalOn, projects, direction, timeWindow, includeDeparted);
    }

    /**
     * 勾选项目 ∩ (用户可访问项目 ∪ 被授权召回项目)；userId null 或空勾选 → 空集（不调 ProjectService）。
     * <p>
     * 记忆二期 P1：被授权召回的项目（ACTIVE 个人授权）一并保留——否则用户在召回范围勾选了授权项目，
     * 这里会被当成「不可访问」过滤掉，授权形同虚设。
     */
    private List<Long> filterAccessible(List<Long> rawProjects, Long userId) {
        if (userId == null || rawProjects.isEmpty()) {
            return List.of();
        }
        Set<Long> accessible = projectService.listAccessibleProjectIds(userId);
        Set<Long> granted = new HashSet<>(grantService.findActiveGrantedProjectIds(userId));
        // 保留用户勾选顺序（前端展示稳定），仅保留 可访问 ∪ 已授权 项
        return rawProjects.stream().filter(pid -> accessible.contains(pid) || granted.contains(pid)).toList();
    }
}
