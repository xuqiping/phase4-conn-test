package com.superprogrammer.chat.service.internal;

import java.util.Collections;
import java.util.List;

/**
 * 记忆可见性 scope（V33 项目记忆）。读/写链路共用。
 * <p>
 * scope = 多对多可见性标签，非所有权分区。一条记忆可见 iff：
 * <ul>
 *   <li>{@code includeGlobal=true} 且记忆 {@code is_global=true}（总记忆）；或</li>
 *   <li>记忆经 user_memory_projects 挂在 {@code enabledProjectIds} 任一项目里。</li>
 * </ul>
 * <p>
 * 两种用途：
 * <ul>
 *   <li><b>读/注入 scope</b>：扁平开关集（{@code includeGlobal} 勾 + 每个项目各勾），注入取并集去重。</li>
 *   <li><b>写/冲突 scope</b>：限定到单个写目标（{@code resolveWriteScope}）——
 *       global 写目标→ {@code (true, [])}；project A 写目标→ {@code (false, [A])}。
 *       冲突候选 = 写目标 scope 内同 key 行（跨 scope 不冲突）。</li>
 * </ul>
 * 向后兼容：老会话/老数据 → {@code (true, [])} = 只读 is_global = 今天行为。
 */
public record MemoryScope(Long userId, boolean includeGlobal, List<Long> enabledProjectIds) {

    /** 全 global scope（老行为：只读总记忆）。 */
    public static MemoryScope globalOnly(Long userId) {
        return new MemoryScope(userId, true, Collections.emptyList());
    }

    /** 防御性非 null。 */
    public List<Long> safeProjectIds() {
        return enabledProjectIds == null ? Collections.emptyList() : enabledProjectIds;
    }
}
