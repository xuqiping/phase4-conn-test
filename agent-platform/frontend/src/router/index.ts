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
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/SettingsView.vue'),
        meta: { title: '设置' }
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
