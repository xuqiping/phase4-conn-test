import { describe, expect, it } from 'vitest'
import { resolveRouteAccess, type UserContext } from './accessGuard'

const LANDING = '/chat'
const LOGIN = '/login'

function admin(): UserContext {
  return { isAdmin: true, permissions: [] }
}
function userWith(perms: string[] = []): UserContext {
  return { isAdmin: false, permissions: perms }
}
function guest(): UserContext {
  return { isAdmin: false, permissions: [] }
}

/** 调用便捷封装：默认已登录、默认落地 /chat、登录页 /login。 */
function decide(overrides: Partial<Parameters<typeof resolveRouteAccess>[0]>) {
  return resolveRouteAccess({
    path: '/x',
    requiresAuth: true,
    hasToken: true,
    user: guest(),
    defaultLanding: LANDING,
    loginPath: LOGIN,
    ...overrides
  })
}

describe('resolveRouteAccess 路由访问判定（问题 10x-3/4/5）', () => {
  describe('10x-5 关闭模块拦截（含 admin）', () => {
    it('admin 访问关闭的 agentHall 模块 → 重定向落地页', () => {
      const d = decide({ path: '/agents', module: 'agentHall', user: admin() })
      expect(d.allow).toBe(false)
      if (!d.allow) expect(d.redirect).toBe(LANDING)
    })

    it('admin 访问关闭的 workflow 模块 → 重定向', () => {
      const d = decide({ path: '/workflow', module: 'workflow', user: admin() })
      expect(d.allow).toBe(false)
    })

    it('admin 访问关闭的 execution 模块 → 重定向', () => {
      const d = decide({ path: '/executions', module: 'execution', user: admin() })
      expect(d.allow).toBe(false)
    })

    it('普通用户访问关闭模块同样被拦', () => {
      const d = decide({ path: '/agents', module: 'agentHall', user: userWith(['agent:read']) })
      expect(d.allow).toBe(false)
    })
  })

  describe('10x-3 设置仅 admin', () => {
    it('非 admin 访问 requireAdmin 路由 → 重定向', () => {
      const d = decide({ path: '/settings', module: 'settings', requireAdmin: true, user: userWith() })
      expect(d.allow).toBe(false)
      if (!d.allow) expect(d.redirect).toBe(LANDING)
    })

    it('admin 访问 requireAdmin 路由 → 放行', () => {
      const d = decide({ path: '/settings', module: 'settings', requireAdmin: true, user: admin() })
      expect(d.allow).toBe(true)
    })
  })

  describe('10x-4 模块权限码兜底', () => {
    it('普通用户无 media:gen 访问 /video-gen → 拦截', () => {
      const d = decide({ path: '/video-gen', module: 'videoGen', user: userWith([]) })
      expect(d.allow).toBe(false)
    })

    it('普通用户持 media:gen 访问 /video-gen → 放行', () => {
      const d = decide({ path: '/video-gen', module: 'videoGen', user: userWith(['media:gen']) })
      expect(d.allow).toBe(true)
    })

    it('admin 无显式权限码也放行（admin 默认全权限）', () => {
      const d = decide({ path: '/video-gen', module: 'videoGen', user: admin() })
      expect(d.allow).toBe(true)
    })

    it('无权限码的模块（chat）对所有登录用户放行', () => {
      const d = decide({ path: '/chat', module: 'chat', user: userWith([]) })
      expect(d.allow).toBe(true)
    })
  })

  describe('认证', () => {
    it('未登录访问受保护路由 → 跳登录页', () => {
      const d = decide({ path: '/chat', requiresAuth: true, hasToken: false, user: guest() })
      expect(d.allow).toBe(false)
      if (!d.allow) expect(d.redirect).toBe(LOGIN)
    })

    it('已登录访问登录页 → 跳落地页', () => {
      const d = decide({ path: LOGIN, requiresAuth: false, hasToken: true, user: guest() })
      expect(d.allow).toBe(false)
      if (!d.allow) expect(d.redirect).toBe(LANDING)
    })

    it('公开路由（登录页）未登录放行', () => {
      const d = decide({ path: LOGIN, requiresAuth: false, hasToken: false, user: guest() })
      expect(d.allow).toBe(true)
    })
  })

  describe('无 meta.module 的路由', () => {
    it('不带模块标签的路由仅做认证判定（向后兼容）', () => {
      const d = decide({ path: '/anything', module: undefined, user: userWith([]) })
      expect(d.allow).toBe(true)
    })
  })

  describe('19x 反馈 admin 双码分离（feedbackAdmin/helpAdmin）', () => {
    it('无 feedback:manage 直输 /admin/feedback → 拦截', () => {
      const d = decide({ path: '/admin/feedback', module: 'feedbackAdmin', user: userWith([]) })
      expect(d.allow).toBe(false)
      if (!d.allow) expect(d.redirect).toBe(LANDING)
    })

    it('持 feedback:manage → 放行反馈处理页', () => {
      const d = decide({ path: '/admin/feedback', module: 'feedbackAdmin', user: userWith(['feedback:manage']) })
      expect(d.allow).toBe(true)
    })

    it('持 feedback:manage 但无 help:manage → 帮助文章页仍拦截（双码分离）', () => {
      const d = decide({ path: '/admin/help-articles', module: 'helpAdmin', user: userWith(['feedback:manage']) })
      expect(d.allow).toBe(false)
    })

    it('持 help:manage → 放行帮助文章页；admin 两页皆放行', () => {
      expect(decide({ path: '/admin/help-articles', module: 'helpAdmin', user: userWith(['help:manage']) }).allow).toBe(true)
      expect(decide({ path: '/admin/feedback', module: 'feedbackAdmin', user: admin() }).allow).toBe(true)
      expect(decide({ path: '/admin/help-articles', module: 'helpAdmin', user: admin() }).allow).toBe(true)
    })
  })

  describe('7x 追加 支付渠道配置（paymentAdmin→payment:config）', () => {
    it('无 payment:config 直输 /admin/payment-channels → 拦截；持码放行', () => {
      const denied = decide({ path: '/admin/payment-channels', module: 'paymentAdmin', user: userWith([]) })
      expect(denied.allow).toBe(false)
      expect(decide({ path: '/admin/payment-channels', module: 'paymentAdmin', user: userWith(['payment:config']) }).allow).toBe(true)
      expect(decide({ path: '/admin/payment-channels', module: 'paymentAdmin', user: admin() }).allow).toBe(true)
    })
  })

  describe('16x requireAnyPerm 豁免（大模型配置员进设置）', () => {
    it('非 admin 持 llm:config 访问 /settings → 放行', () => {
      const d = decide({
        path: '/settings', module: 'settings', requireAdmin: true,
        requireAnyPerm: ['llm:config'], user: userWith(['llm:config'])
      })
      expect(d.allow).toBe(true)
    })

    it('非 admin 无 llm:config 访问 /settings → 仍拦截', () => {
      const d = decide({
        path: '/settings', module: 'settings', requireAdmin: true,
        requireAnyPerm: ['llm:config'], user: userWith(['pricing:manage'])
      })
      expect(d.allow).toBe(false)
      if (!d.allow) expect(d.redirect).toBe(LANDING)
    })

    it('requireAdmin 路由不带 requireAnyPerm 时行为不变（回归）', () => {
      const d = decide({ path: '/settings', module: 'settings', requireAdmin: true, user: userWith(['llm:config']) })
      expect(d.allow).toBe(false)
    })

    it('admin 访问带 requireAnyPerm 的路由 → 放行（不受影响）', () => {
      const d = decide({
        path: '/settings', module: 'settings', requireAdmin: true,
        requireAnyPerm: ['llm:config'], user: admin()
      })
      expect(d.allow).toBe(true)
    })
  })
})
