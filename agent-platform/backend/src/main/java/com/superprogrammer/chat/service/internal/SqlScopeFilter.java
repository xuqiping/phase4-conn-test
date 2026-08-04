package com.superprogrammer.chat.service.internal;

import java.util.Collections;
import java.util.List;

/**
 * 记忆 scope 过滤的公共参数契约（V47 计划12·迭代 A）。
 *
 * <p>流水账（memory_turns）的可见范围 = 自己的（user_id=self，含个人出身+后共享）
 * <b>OR</b> 挂在 accessible 项目集内的（经 ACL，向量 1/2）。对应 XML 片段
 * {@code <sql id="SCOPE_FILTER">} 见 MemoryTurnMapper.xml。
 *
 * <p>accessibleProjectIds 由 service 按 readableAuthors 算（I1 迭代 V48）。
 * 此处只提供参数键 + 空集归一（空集 → project_ids &amp;&amp; '{}' = false，只剩自己的，避免 SQL null 报错）。
 *
 * <p>总结/覆盖/冲突三表恒只读自己（user_id=self），不走本片段——见各自 mapper XML 内联 {@code AND user_id}。
 */
public final class SqlScopeFilter {

    public static final String PARAM_USER_ID = "userId";
    public static final String PARAM_ACCESSIBLE_PROJECT_IDS = "accessibleProjectIds";

    private SqlScopeFilter() {}

    /** null → 空集，防 project_ids &amp;&amp; null 报错。 */
    public static List<Long> normalizeAccessible(List<Long> accessibleProjectIds) {
        return accessibleProjectIds == null ? Collections.emptyList() : accessibleProjectIds;
    }
}
