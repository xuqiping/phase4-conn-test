import { isModuleEnabled, getModulePermission, type ModuleKey } from '@/config/modules'

/**
 * 路由访问判定结果。
 * - allow：放行
 * - redirect：重定向到给定路径（默认落地页/登录页）
 */
export type AccessDecision =
  | { allow: true }
  | { allow: false; redirect: string }

/**
 * 用户上下文（守卫从 localStorage 读，测试可直接构造）。
 *isAdmin=true 视为持全权限（与后端 admin 角色语义一致）。
 */
export interface UserContext {
  isAdmin: boolean
  permissions: string[]
}

/**
 * 路由访问控制纯函数（问题 10x-3/4/5 的核心逻辑）。
 *
 * 判定顺序（短路）：
 * 1. 未登录访问受保护路由 → 跳登录页；
 * 2. 已登录访问 /login → 跳默认落地页；
 * 3. 关闭模块路由（meta.module 对应开关 false）→ 跳默认落地页（含 admin，10x-5）；
 * 4. requireAdmin 路由非 admin → 跳默认落地页（10x-3）；
 *    例外：meta.requireAnyPerm 命中其一即放行（16x：llm_config 角色可进 /settings 配大模型）；
 * 5. 模块有权限码且用户未持有（admin 视为持有）→ 跳默认落地页（10x-4）；
 * 6. 其余放行。
 *
 * 抽成纯函数便于单元测试，避开真实懒加载导航在 jsdom 下的不可控。
 * beforeEach 守卫读 localStorage 构造 UserContext 后调用本函数。
 */
export function resolveRouteAccess(params: {
  path: string
  requiresAuth: boolean
  hasToken: boolean
  module?: ModuleKey
  requireAdmin?: boolean
  /** requireAdmin 的豁免白名单：持有其中任一权限码的非 admin 也放行（16x） */
  requireAnyPerm?: string[]
  user: UserContext
  defaultLanding: string
  loginPath: string
}): AccessDecision {
  const { path, requiresAuth, hasToken, module: moduleKey, requireAdmin, requireAnyPerm, user, defaultLanding, loginPath } = params

  // 1. 未登录访问受保护路由 → 登录页
  if (requiresAuth && !hasToken) {
    return { allow: false, redirect: loginPath }
  }

  // 2. 已登录访问登录页 → 默认落地页
  if (path === loginPath && hasToken) {
    return { allow: false, redirect: defaultLanding }
  }

  // 3. 关闭模块拦截（含 admin，10x-5）
  if (moduleKey && !isModuleEnabled(moduleKey)) {
    return { allow: false, redirect: defaultLanding }
  }

  // 4. requireAdmin 非 admin 拦截（10x-3）；requireAnyPerm 命中其一豁免（16x）
  if (requireAdmin && !user.isAdmin) {
    const exempt = requireAnyPerm?.some(p => user.permissions.includes(p)) ?? false
    if (!exempt) {
      return { allow: false, redirect: defaultLanding }
    }
  }

  // 5. 模块权限码校验（admin 视为持有，10x-4）
  if (moduleKey) {
    const perm = getModulePermission(moduleKey)
    if (perm && !user.isAdmin && !user.permissions.includes(perm)) {
      return { allow: false, redirect: defaultLanding }
    }
  }

  return { allow: true }
}
