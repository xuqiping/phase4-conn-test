package com.superprogrammer.common.security;

import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * 安全端点登记处（安全体系 S2 · B1，SEC-FR-010）：权限注解覆盖策略的单一事实源。
 *
 * <p>三类登记：
 * <ul>
 *   <li>{@link #PUBLIC_PATHS} 免认证白名单——覆盖 scanner 评估的 `/api` 前缀端点，与 SecurityConfig
 *       中 /api 的 permitAll 严格对齐（/ws/chat、/actuator/* 等非 /api permitAll 不属 scanner 评估面，
 *       由 SecurityConfig 独立管辖），新增公开端点两处同步（ scanner 与白名单不一致会在启动日志现形）；</li>
 *   <li>{@link #AUTHENTICATED_ONLY_REVIEWED} 已评审「仅登录即可」端点——无方法级权限是有意设计
 *       （用户域数据由 service 层归属/ACL 校验兜底），每条带评审依据注释；</li>
 *   <li>其余无 {@code @RequirePermission}/{@code @PreAuthorize} 的端点 = 未覆盖，
 *       启动期 {@link PermissionCoverageScanner} WARN 清单 + 计数指标。</li>
 * </ul>
 * 红线：新增端点要么标注解，要么显式登记进上面两张表并写明依据——不许静默裸奔。
 * 注意：scanner 只认识方法/类级注解与本表白名单，仅靠 SecurityConfig URL 规则
 * （requestMatchers+hasAuthority）保护的端点会被误判未覆盖——新端点不要用那种写法。
 */
public final class SecurityEndpointRegistry {

    private SecurityEndpointRegistry() {
    }

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 免认证白名单（与 SecurityConfig permitAll 对齐）。 */
    public static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/login/dingtalk",
            // sidecar 回调：独立 RuntimeCallbackSecurityFilter 持 token 校验（fail-closed）
            "/api/runtime/callbacks/**",
            // Ark 参考视频回拉：控制器内 HMAC + expires fail-closed。
            "/api/media/reference/**"
    );

    /** 已评审「仅登录即可」端点（无方法级权限的依据见每条注释）。 */
    public static final List<String> AUTHENTICATED_ONLY_REVIEWED = List.of(
            "/api/auth/logout",          // 登出只需持本人 token
            "/api/auth/me",              // 读本人信息
            "/api/chat/**",              // 会话/记忆用户域：service 层归属校验 + V49 召回 ACL
            "/api/llm/user/**",          // 用户自有 provider CRUD（按 userId 隔离）
            "/api/files/**",             // S1 文件 owner 校验咽喉（非 owner 非 admin → 403）
            "/api/projects/**",          // 项目创建者作用域
            "/api/billing/me/**",        // 本人钱包/用量只读视图（userId 取自 SecurityContext，无入参旁路）
            "/api/departments"           // 组织架构只读引用数据（VO 无敏感字段，内网常规语义；写操作均 role:manage）
    );

    public enum Coverage {
        /** 有 @RequirePermission/@PreAuthorize 方法级权限。 */
        GUARDED_BY_ANNOTATION,
        /** 免认证白名单。 */
        PUBLIC_WHITELIST,
        /** 已评审仅登录。 */
        AUTH_ONLY_REVIEWED,
        /** 未覆盖：启动 WARN 待评审。 */
        UNGUARDED_REVIEW_NEEDED
    }

    /** 归类一个无注解端点路径。 */
    public static Coverage categorize(String path) {
        if (matchesAny(PUBLIC_PATHS, path)) {
            return Coverage.PUBLIC_WHITELIST;
        }
        if (matchesAny(AUTHENTICATED_ONLY_REVIEWED, path)) {
            return Coverage.AUTH_ONLY_REVIEWED;
        }
        return Coverage.UNGUARDED_REVIEW_NEEDED;
    }

    private static boolean matchesAny(List<String> patterns, String path) {
        for (String p : patterns) {
            if (MATCHER.match(p, path)) {
                return true;
            }
        }
        return false;
    }
}
