import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '@/layouts/MainLayout.vue'

// 样式预览版：无登录守卫，默认进画布；5 页共享 MainLayout（侧栏+顶栏）
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: MainLayout,
      children: [
        { path: '', redirect: '/canvas' },
        {
          path: 'canvas',
          name: 'canvas',
          component: () => import('@/views/CanvasView.vue'),
          meta: { title: '无限画布' }
        },
        {
          path: 'chat',
          name: 'chat',
          component: () => import('@/views/ChatView.vue'),
          meta: { title: '对话' }
        },
        {
          path: 'agents',
          name: 'agents',
          component: () => import('@/views/AgentHallView.vue'),
          meta: { title: '智能体大厅' }
        },
        {
          path: 'workflows',
          name: 'workflows',
          component: () => import('@/views/WorkflowListView.vue'),
          meta: { title: '工作流' }
        }
      ]
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
      meta: { title: '页面不存在' }
    }
  ]
})

export default router
