import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import Sidebar from './Sidebar.vue'
import { useAuthStore } from '@/stores/auth'

// Sidebar 渲染 navItems 需要路由（isNavItemActive 读 route.path），mock 一个最小 router-link
vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/' }),
  RouterLink: {
    props: ['to'],
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    setup(_: any, { slots }: any) {
      return () => slots.default?.()
    }
  }
}))

vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, NIcon: actual.NIcon }
})

function mountSidebar() {
  return mount(Sidebar, {
    props: { collapsed: false },
    global: { stubs: { NIcon: true } }
  })
}

describe('Sidebar 模块开关与权限显隐（问题 10x-4/10x-5）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('问题 10x-5：admin 也看不到 Agent大厅/工作流/执行监控（项目级开关关闭）', async () => {
    const auth = useAuthStore()
    auth.userInfo = {
      id: 1, username: 'admin', email: null, avatar: null,
      roles: ['admin'], permissions: []
    }
    const wrapper = mountSidebar()
    const labels = wrapper.findAll('.sidebar__nav-label').map((n) => n.text())
    expect(labels).not.toContain('Agent大厅')
    expect(labels).not.toContain('工作流')
    expect(labels).not.toContain('执行监控')
  })

  it('问题 10x-3：非 admin 用户看不到「设置」入口', async () => {
    const auth = useAuthStore()
    auth.userInfo = {
      id: 2, username: 'user1', email: null, avatar: null,
      roles: ['user'], permissions: []
    }
    const wrapper = mountSidebar()
    const labels = wrapper.findAll('.sidebar__nav-label').map((n) => n.text())
    expect(labels).not.toContain('设置')
    expect(labels).not.toContain('用户管理')
    expect(labels).not.toContain('角色权限')
  })

  it('admin 能看到「设置」入口', async () => {
    const auth = useAuthStore()
    auth.userInfo = {
      id: 1, username: 'admin', email: null, avatar: null,
      roles: ['admin'], permissions: []
    }
    const wrapper = mountSidebar()
    const labels = wrapper.findAll('.sidebar__nav-label').map((n) => n.text())
    expect(labels).toContain('设置')
  })

  it('问题 10x-4：普通用户无 media:gen 权限 → 看不到视频生成/图片生成', async () => {
    const auth = useAuthStore()
    auth.userInfo = {
      id: 2, username: 'user1', email: null, avatar: null,
      roles: ['user'], permissions: [] // 无任何权限码
    }
    const wrapper = mountSidebar()
    const labels = wrapper.findAll('.sidebar__nav-label').map((n) => n.text())
    expect(labels).not.toContain('视频生成')
    expect(labels).not.toContain('图片生成')
    expect(labels).not.toContain('无限画布')
    expect(labels).not.toContain('资产库')
  })

  it('普通用户持有 media:gen 但无 media:edit → 只看到视频/图片生成，看不到视频剪辑', async () => {
    const auth = useAuthStore()
    auth.userInfo = {
      id: 3, username: 'mediaUser', email: null, avatar: null,
      roles: ['user'], permissions: ['media:gen'] // 只给 media:gen
    }
    const wrapper = mountSidebar()
    const labels = wrapper.findAll('.sidebar__nav-label').map((n) => n.text())
    expect(labels).toContain('视频生成')
    expect(labels).toContain('图片生成')
    expect(labels).not.toContain('视频剪辑') // media:edit 未授予
  })

  it('常用模块（对话/知识库/钱包）对所有登录用户常驻可见', async () => {
    const auth = useAuthStore()
    auth.userInfo = {
      id: 2, username: 'user1', email: null, avatar: null,
      roles: ['user'], permissions: []
    }
    const wrapper = mountSidebar()
    const labels = wrapper.findAll('.sidebar__nav-label').map((n) => n.text())
    expect(labels).toContain('智能对话')
    expect(labels).toContain('知识库')
    expect(labels).toContain('我的钱包')
  })
})
