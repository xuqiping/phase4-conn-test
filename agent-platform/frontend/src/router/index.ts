// ============================================================
// Vue Router 配置
// 路由定义 + 导航守卫（认证检查）
// ============================================================

import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { getStorage, STORAGE_KEYS } from '@/utils/storage'
import { isModuleEnabled, type ModuleKey } from '@/config/modules'
import { resolveRouteAccess, type UserContext } from './accessGuard'

/**
 * 路由 meta 扩展（问题 10x-3/10x-4/10x-5）：
 * - module：该路由归属的功能模块 key；为关闭模块时守卫拦截重定向首页
 * - requireAdmin：仅 admin 可访问（如 /settings，10x-3 非 admin 不开放设置）
 * 路由 meta 权限仅做 UX 层重定向，真实授权仍由后端 @RequirePermission + API 403 兜底。
 */
declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    layout?: string
    requiresAuth?: boolean
    /** 归属模块 key；关闭的模块路由被守卫拦截（10x-5） */
    module?: ModuleKey
    /** 仅 admin 可访问（10x-3 设置模块） */
    requireAdmin?: boolean
  }
}

/**
 * 登录后/根路径默认跳转目标：选第一个启用的常驻模块（10x-5 后 /agents 已关闭，
 * 不能再硬编码 /agents 否则登录后白屏）。优先级：chat → knowledge → wallet。
 */
function defaultLanding(): string {
  const candidates: ModuleKey[] = ['chat', 'knowledge', 'wallet']
  for (const m of candidates) {
    if (isModuleEnabled(m)) return `/${m === 'chat' ? 'chat' : m === 'knowledge' ? 'knowledge' : 'wallet'}`
  }
  return '/chat'
}

// 路由定义
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: {
      layout: 'auth',
      title: '登录',
      requiresAuth: false
    }
  },
  {
    path: '/dingtalk/callback',
    name: 'DingTalkCallback',
    component: () => import('@/views/DingTalkCallbackView.vue'),
    meta: { requiresAuth: false, title: '钉钉登录' }
  },
  {
    // 认证系统增强：邮箱激活落地页（邮件链接 ?token=xxx）
    path: '/verify-email',
    name: 'VerifyEmail',
    component: () => import('@/views/VerifyEmailView.vue'),
    meta: { requiresAuth: false, layout: 'auth', title: '邮箱验证' }
  },
  {
    // 认证系统增强：重置密码落地页（邮件链接 ?token=xxx 或短信 ?channel=sms）
    path: '/reset-password',
    name: 'ResetPassword',
    component: () => import('@/views/ResetPasswordView.vue'),
    meta: { requiresAuth: false, layout: 'auth', title: '重置密码' }
  },
  {
    // 安全体系 S5 · J4：隐私政策独立页（注册弹窗简版条款的完整口径）
    path: '/privacy',
    name: 'PrivacyPolicy',
    component: () => import('@/views/PrivacyPolicyView.vue'),
    meta: { requiresAuth: false, layout: 'auth', title: '隐私政策' }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        redirect: defaultLanding()
      },
      {
        path: 'agents',
        name: 'AgentHall',
        component: () => import('@/views/AgentHallView.vue'),
        // 10x-5：本项目未启用 Agent大厅，module 门控让守卫拦截手敲 URL
        meta: { title: 'Agent大厅', module: 'agentHall' }
      },
      {
        path: 'agents/:id',
        name: 'AgentDetail',
        component: () => import('@/views/AgentDetailView.vue'),
        meta: { title: 'Agent详情', module: 'agentHall' }
      },
      {
        path: 'chat',
        name: 'Chat',
        component: () => import('@/views/ChatView.vue'),
        meta: { title: '智能对话', module: 'chat' }
      },
      {
        path: 'chat/:sessionId',
        name: 'ChatSession',
        component: () => import('@/views/ChatView.vue'),
        meta: { title: '智能对话', module: 'chat' }
      },
      {
        path: 'workflow',
        name: 'WorkflowList',
        component: () => import('@/views/WorkflowListView.vue'),
        // 10x-5：本项目未启用工作流
        meta: { title: '工作流列表', module: 'workflow' }
      },
      {
        path: 'workflow/:id',
        name: 'WorkflowEditor',
        component: () => import('@/views/WorkflowEditorView.vue'),
        meta: { title: '工作流编辑器', module: 'workflow' }
      },
      {
        path: 'executions',
        name: 'ExecutionMonitor',
        component: () => import('@/views/ExecutionMonitorView.vue'),
        // 10x-5：本项目未启用执行监控
        meta: { title: '执行监控', module: 'execution' }
      },
      {
        path: 'knowledge',
        name: 'Knowledge',
        component: () => import('@/views/KnowledgeView.vue'),
        meta: { title: '知识库', module: 'knowledge' }
      },
      {
        path: 'video-gen',
        name: 'VideoGen',
        component: () => import('@/views/VideoGenView.vue'),
        // module 门控（10x-4）：菜单隐藏 + 页内 canGen + 路由守卫 + API 403 四重兜底
        meta: { title: '视频生成', module: 'videoGen' }
      },
      {
        path: 'image-gen',
        name: 'ImageGen',
        component: () => import('@/views/ImageGenView.vue'),
        meta: { title: '图片生成', module: 'imageGen' }
      },
      {
        path: 'video-edit',
        name: 'VideoEdit',
        component: () => import('@/views/VideoEditView.vue'),
        // 同 video-gen：菜单隐藏(hasPermission('media:edit')) + 页内 canEdit + 守卫 + API 403
        meta: { title: '视频剪辑', module: 'videoEdit' }
      },
      {
        path: 'canvas',
        name: 'CanvasList',
        component: () => import('@/views/CanvasView.vue'),
        // 同 video-gen：菜单隐藏 + 页内 canEdit(canvas:write) + 守卫 + API 403
        meta: { title: '无限画布', module: 'canvas' }
      },
      {
        path: 'canvas/:id',
        name: 'CanvasEditor',
        component: () => import('@/views/CanvasView.vue'),
        meta: { title: '无限画布', module: 'canvas' }
      },
      {
        path: 'assets',
        name: 'AssetList',
        component: () => import('@/views/AssetListView.vue'),
        // 同 canvas：菜单隐藏（Sidebar hasPermission('asset:write')）+ 页内 canEdit + 守卫 + API 403
        meta: { title: '资产库', module: 'assets' }
      },
      {
        path: 'assets/:id',
        name: 'AssetProject',
        component: () => import('@/views/AssetProjectView.vue'),
        meta: { title: '项目资产', module: 'assets' }
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/SettingsView.vue'),
        // 10x-3：设置仅 admin 可见（非 admin 守卫拦截重定向首页）
        meta: { title: '设置', module: 'settings', requireAdmin: true }
      },
      {
        path: 'wallet',
        name: 'MyWallet',
        component: () => import('@/views/MyWalletView.vue'),
        // 所有登录用户可见自己的钱包（仅积分，无 token/¥）；权限靠 API ownership 兜底
        meta: { title: '我的钱包', module: 'wallet' }
      },
      {
        path: 'project-groups',
        name: 'ProjectGroups',
        component: () => import('@/views/ProjectGroupsView.vue'),
        // 计划5 Step7：项目组推进页（我的组卡片→组详情）；菜单按 project-group:manage 隐藏，API 二层兜底
        meta: { title: '项目组', module: 'projectGroups' }
      },
      {
        path: 'admin/users',
        name: 'UserManage',
        component: () => import('@/views/admin/UserManageView.vue'),
        meta: { title: '用户管理' }
      },
      {
        path: 'admin/roles',
        name: 'RoleManage',
        component: () => import('@/views/admin/RoleManageView.vue'),
        meta: { title: '角色权限' }
      },
      {
        path: 'admin/billing',
        name: 'BillingAdmin',
        component: () => import('@/views/BillingAdminView.vue'),
        // 菜单隐藏(hasPermission usage:view) + 页内 canView + API 403 三重兜底
        meta: { title: '账单总览' }
      },
      {
        path: 'admin/billing-pricing',
        name: 'PricingConfig',
        component: () => import('@/views/admin/PricingConfigView.vue'),
        // pricing:manage 三重兜底
        meta: { title: '价表配置' }
      },
      {
        path: 'admin/billing-wallet',
        name: 'WalletAdmin',
        component: () => import('@/views/admin/WalletAdminView.vue'),
        // points:recharge 三重兜底
        meta: { title: '积分充值' }
      },
      {
        path: 'admin/logs/audit',
        name: 'AuditLog',
        component: () => import('@/views/admin/logs/AuditLogView.vue'),
        // system:audit:read 三重兜底（菜单隐藏 + 页内 canView + API 403）
        meta: { title: '审计日志' }
      },
      // 11x 加固 P4-C12：安全管理 4 页（security:* 权限三重兜底：菜单隐藏 + API 403 + 后端 @RequirePermission）
      {
        path: 'admin/security/dashboard',
        name: 'RiskDashboard',
        component: () => import('@/views/admin/security/RiskDashboardView.vue'),
        meta: { title: '风险大盘' }
      },
      {
        path: 'admin/security/events',
        name: 'SecurityEvent',
        component: () => import('@/views/admin/security/SecurityEventView.vue'),
        meta: { title: '安全事件中心' }
      },
      {
        path: 'admin/security/ban',
        name: 'BanManage',
        component: () => import('@/views/admin/security/BanManageView.vue'),
        meta: { title: '封禁管理' }
      },
      {
        path: 'admin/security/rules',
        name: 'RuleConfig',
        component: () => import('@/views/admin/security/RuleConfigView.vue'),
        meta: { title: '安全规则配置' }
      }
    ]
  },
  // 兜底路由：未匹配的路径重定向到首页（10x-5 后默认落地页不再是 /agents）
  {
    path: '/:pathMatch(.*)*',
    redirect: defaultLanding()
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 从 localStorage 构造用户上下文（守卫早于 Pinia 初始化，不能依赖 store）。
 * 与 stores/auth 的 UserInfo.roles/permissions 同源（登录时写 STORAGE_KEYS.USER_INFO）。
 */
function readUserContext(): UserContext {
  try {
    const raw = getStorage<{ roles?: string[]; permissions?: string[] }>(STORAGE_KEYS.USER_INFO)
    return {
      isAdmin: !!raw?.roles?.includes('admin'),
      permissions: raw?.permissions ?? []
    }
  } catch {
    return { isAdmin: false, permissions: [] }
  }
}

const LOGIN_PATH = '/login'

// 全局前置守卫 — 认证 + 模块开关 + admin 门控（逻辑见 accessGuard.ts 纯函数）
router.beforeEach((to, _from, next) => {
  // 设置页面标题
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} - 多Agent智能体平台` : '多Agent智能体平台'

  const decision = resolveRouteAccess({
    path: to.path,
    requiresAuth: to.meta.requiresAuth !== false,
    hasToken: !!getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN),
    module: to.meta.module,
    requireAdmin: to.meta.requireAdmin,
    user: readUserContext(),
    defaultLanding: defaultLanding(),
    loginPath: LOGIN_PATH
  })

  if (decision.allow) {
    next()
  } else if (decision.redirect === LOGIN_PATH) {
    // 未登录跳登录页时带上回跳地址
    next({ path: LOGIN_PATH, query: { redirect: to.fullPath } })
  } else {
    next({ path: decision.redirect })
  }
})

export default router
