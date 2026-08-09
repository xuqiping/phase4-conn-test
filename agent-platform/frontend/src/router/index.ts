// ============================================================
// Vue Router 配置
// 路由定义 + 导航守卫（认证检查）
// ============================================================

import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { getStorage, STORAGE_KEYS } from '@/utils/storage'

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
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        redirect: '/agents'
      },
      {
        path: 'agents',
        name: 'AgentHall',
        component: () => import('@/views/AgentHallView.vue'),
        meta: { title: 'Agent大厅' }
      },
      {
        path: 'agents/:id',
        name: 'AgentDetail',
        component: () => import('@/views/AgentDetailView.vue'),
        meta: { title: 'Agent详情' }
      },
      {
        path: 'chat',
        name: 'Chat',
        component: () => import('@/views/ChatView.vue'),
        meta: { title: '智能对话' }
      },
      {
        path: 'chat/:sessionId',
        name: 'ChatSession',
        component: () => import('@/views/ChatView.vue'),
        meta: { title: '智能对话' }
      },
      {
        path: 'workflow',
        name: 'WorkflowList',
        component: () => import('@/views/WorkflowListView.vue'),
        meta: { title: '工作流列表' }
      },
      {
        path: 'workflow/:id',
        name: 'WorkflowEditor',
        component: () => import('@/views/WorkflowEditorView.vue'),
        meta: { title: '工作流编辑器' }
      },
      {
        path: 'executions',
        name: 'ExecutionMonitor',
        component: () => import('@/views/ExecutionMonitorView.vue'),
        meta: { title: '执行监控' }
      },
      {
        path: 'knowledge',
        name: 'Knowledge',
        component: () => import('@/views/KnowledgeView.vue'),
        meta: { title: '知识库' }
      },
      {
        path: 'video-gen',
        name: 'VideoGen',
        component: () => import('@/views/VideoGenView.vue'),
        // 路由 meta 仅 requiresAuth（平台惯例不按权限卡路由，靠菜单隐藏+页内 canGen+API 403 三重兜底）
        meta: { title: '视频生成' }
      },
      {
        path: 'image-gen',
        name: 'ImageGen',
        component: () => import('@/views/ImageGenView.vue'),
        meta: { title: '图片生成' }
      },
      {
        path: 'canvas',
        name: 'CanvasList',
        component: () => import('@/views/CanvasView.vue'),
        // 同 video-gen：菜单隐藏 + 页内 canEdit(canvas:write) + API 403 三重兜底
        meta: { title: '无限画布' }
      },
      {
        path: 'canvas/:id',
        name: 'CanvasEditor',
        component: () => import('@/views/CanvasView.vue'),
        meta: { title: '无限画布' }
      },
      {
        path: 'assets',
        name: 'AssetList',
        component: () => import('@/views/AssetListView.vue'),
        // 同 canvas：菜单隐藏（Sidebar hasPermission('asset:write')）+ 页内 canEdit + API 403 三重兜底
        meta: { title: '资产库' }
      },
      {
        path: 'assets/:id',
        name: 'AssetProject',
        component: () => import('@/views/AssetProjectView.vue'),
        meta: { title: '项目资产' }
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/SettingsView.vue'),
        meta: { title: '设置' }
      },
      {
        path: 'wallet',
        name: 'MyWallet',
        component: () => import('@/views/MyWalletView.vue'),
        // 所有登录用户可见自己的钱包（仅积分，无 token/¥）；权限靠 API ownership 兜底
        meta: { title: '我的钱包' }
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
      }
    ]
  },
  // 兜底路由：未匹配的路径重定向到首页
  {
    path: '/:pathMatch(.*)*',
    redirect: '/agents'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局前置守卫 — 认证检查
router.beforeEach((to, _from, next) => {
  // 设置页面标题
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} - 多Agent智能体平台` : '多Agent智能体平台'

  // 检查是否需要认证
  const requiresAuth = to.meta.requiresAuth !== false
  const hasToken = !!getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN)

  if (requiresAuth && !hasToken) {
    // 需要认证但没有token，重定向到登录页
    next({
      path: '/login',
      query: { redirect: to.fullPath }
    })
  } else if (to.path === '/login' && hasToken) {
    // 已登录用户访问登录页，重定向到首页
    next({ path: '/agents' })
  } else {
    next()
  }
})

export default router
