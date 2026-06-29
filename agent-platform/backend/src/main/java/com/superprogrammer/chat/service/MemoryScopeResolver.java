package com.superprogrammer.chat.service;

import com.superprogrammer.chat.entity.ChatSession;
import com.superprogrammer.chat.service.internal.MemoryScope;
import com.superprogrammer.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 记忆 scope 解析（V33），照 {@link com.superprogrammer.knowledge.service.RagScopeResolver} /
 * {@link com.superprogrammer.knowledge.service.RagModeResolver} 形状。
 * <p>
 * 从 {@link ChatSession} 的三个 scope 列解析出读/写 {@link MemoryScope}：
 * <ul>
 *   <li>{@code memIncludeGlobal}（读开关：总记忆 on/off）</li>
 *   <li>{@code memReadProjectIds}（读开关：开启读取的项目集合）</li>
 *   <li>{@code projectId}（写目标：新事实落这，null=global）</li>
 * </ul>
 * 读 scope 的项目集 ∩ 用户可用项目（owner/member/admin）过滤，防引用被撤权的项目记忆泄漏。
 */
@Service
@RequiredArgsConstructor
public class MemoryScopeResolver {

    private final ProjectService projectService;

    /** 读/注入 scope：扁平开关集（includeGlobal + memReadProjectIds 经权限过滤）。 */
    public MemoryScope resolveReadScope(ChatSession session, Long userId, boolean admin) {
        if (session == null || userId == null) {
            return MemoryScope.globalOnly(userId);
        }
        boolean includeGlobal = session.getMemIncludeGlobal() == null || session.getMemIncludeGlobal();
        List<Long> raw = session.getMemReadProjectIds();
        List<Long> enabled = filterAccessible(raw, userId, admin);
        return new MemoryScope(userId, includeGlobal, enabled);
    }

    /** 写/冲突 scope：限定到单个写目标。global 写目标→(true,[])；project→(false,[target])。 */
    public MemoryScope resolveWriteScope(ChatSession session, Long userId, boolean admin) {
        if (session == null || userId == null) {
            return MemoryScope.globalOnly(userId);
        }
        Long target = session.getProjectId();
        if (target == null) {
            return new MemoryScope(userId, true, Collections.emptyList());
        }
        // 写目标须在用户可用项目内，否则降级 global（防越权写他人项目）
        if (!projectService.canAccess(target, userId, admin)) {
            return new MemoryScope(userId, true, Collections.emptyList());
        }
        return new MemoryScope(userId, false, List.of(target));
    }

    /** 写目标 project id（null=global）。 */
    public Long resolveWriteTarget(ChatSession session) {
        return session == null ? null : session.getProjectId();
    }

    private List<Long> filterAccessible(List<Long> raw, Long userId, boolean admin) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> accessible = projectService.listAccessibleProjectIds(userId);
        return raw.stream()
                .filter(accessible::contains)
                .collect(Collectors.toList());
    }
}
