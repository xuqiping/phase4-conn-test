import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AssetListView from './AssetListView.vue'
import { projectApi } from '@/api/assets'
import { useAuthStore } from '@/stores/auth'
import type { AxiosResponse } from 'axios'
import type { AssetProjectVO } from '@/types/asset'

// naive-ui：仅覆盖 useMessage/useDialog（保留真实组件，同 AgentDetailView 范式）
// 用稳定单例，确保组件 setup 捕获的 dialog 与测试断言的是同一实例
const dialogMock = { warning: vi.fn() }
const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return {
    ...actual,
    useMessage: () => messageMock,
    useDialog: () => dialogMock
  }
})

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() })
}))

vi.mock('@/api/assets', () => ({
  projectApi: {
    list: vi.fn(),
    create: vi.fn(),
    remove: vi.fn()
  }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function mkProject(id: number, role: 'OWNER' | 'EDITOR' | 'VIEWER'): AssetProjectVO {
  return {
    id,
    name: `项目${id}`,
    description: 'desc',
    ownerId: 1,
    narrativeRoles: ['人物', '道具'],
    mediaTypes: [{ key: '提示词', category: 'TEXT' }],
    role,
    createdAt: '2026-08-05'
  }
}

function mountView(permissions: string[]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const authStore = useAuthStore()
  authStore.userInfo = {
    id: 1,
    username: 'tester',
    email: null,
    avatar: null,
    roles: ['tester'],
    permissions
  }
  return mount(AssetListView, { global: { plugins: [pinia] } })
}

describe('AssetListView (S9 项目列表页)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(projectApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [mkProject(1, 'OWNER'), mkProject(2, 'EDITOR'), mkProject(3, 'VIEWER')] })
    )
    vi.mocked(projectApi.create).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkProject(9, 'OWNER') })
    )
    vi.mocked(projectApi.remove).mockResolvedValue(response({ code: 200, message: 'ok', data: undefined as never }))
  })

  it('按 role 拆「我的/共享」', async () => {
    const wrapper = mountView(['asset:write'])
    await Promise.resolve()
    await Promise.resolve()

    const vm = wrapper.vm as unknown as {
      mineProjects: AssetProjectVO[]
      sharedProjects: AssetProjectVO[]
    }
    expect(vm.mineProjects.map((p) => p.id)).toEqual([1])
    expect(vm.sharedProjects.map((p) => p.id)).toEqual([2, 3])
  })

  it('无 asset:write 渲染 403 兜底', async () => {
    const wrapper = mountView([])
    await Promise.resolve()
    expect((wrapper.vm as unknown as { canEdit: boolean }).canEdit).toBe(false)
    expect(wrapper.text()).toContain('无 asset:write 权限')
  })

  it('新建项目调 projectApi.create（trim + 空 desc → undefined）', async () => {
    const wrapper = mountView(['asset:write'])
    await Promise.resolve()
    const vm = wrapper.vm as unknown as {
      openCreate: () => void
      form: { name: string; description: string }
      submitCreate: () => Promise<void>
    }
    vm.openCreate()
    vm.form.name = '  我的短剧  '
    vm.form.description = '   '
    await vm.submitCreate()

    expect(projectApi.create).toHaveBeenCalledWith({ name: '我的短剧', description: undefined })
  })

  it('删除项目二次确认 onPositiveClick 调 projectApi.remove', async () => {
    const wrapper = mountView(['asset:write'])
    await Promise.resolve()
    const vm = wrapper.vm as unknown as {
      confirmDelete: (p: AssetProjectVO) => void
    }
    vm.confirmDelete(mkProject(1, 'OWNER'))

    // dialogMock 单例 = 组件 setup 捕获的同一 dialog
    expect(dialogMock.warning).toHaveBeenCalled()
    const opts = dialogMock.warning.mock.calls[0][0] as { onPositiveClick: () => Promise<void> }
    await opts.onPositiveClick()
    expect(projectApi.remove).toHaveBeenCalledWith(1)
  })
})
