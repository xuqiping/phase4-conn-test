import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AgentDetailView from './AgentDetailView.vue'
import { agentApi } from '@/api/agent'
import { useAuthStore } from '@/stores/auth'
import type { AxiosResponse } from 'axios'

vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return {
    ...actual,
    useMessage: () => ({
      success: vi.fn(),
      error: vi.fn()
    }),
    useDialog: () => ({
      warning: vi.fn()
    })
  }
})

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '3' } }),
  useRouter: () => ({ push: vi.fn() }),
  RouterLink: { template: '<a><slot /></a>' }
}))

vi.mock('@/api/agent', () => ({
  agentApi: {
    getGroups: vi.fn(),
    getAgentDetail: vi.fn(),
    getAgentAccess: vi.fn(),
    getSkillDetail: vi.fn(),
    deleteSkill: vi.fn()
  }
}))

function mountView(permissions: string[], accessOverride: Partial<{
  canUse: boolean
  canReadPrompt: boolean
  canCopy: boolean
  canManage: boolean
}> = {}) {
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

  vi.mocked(agentApi.getGroups).mockResolvedValue(response({ code: 200, message: 'ok', data: [] }))
  vi.mocked(agentApi.getAgentAccess).mockResolvedValue(response({
    code: 200,
    message: 'ok',
    data: {
      agentId: 3,
      canUse: true,
      canReadPrompt: false,
      canCopy: false,
      canManage: permissions.some(permission =>
        ['agent:update', 'agent:delete', 'agent:publish', 'agent:manage'].includes(permission)
      ),
      ...accessOverride
    }
  }))
  vi.mocked(agentApi.getAgentDetail).mockResolvedValue(response({
    code: 200,
    message: 'ok',
    data: {
      id: 3,
      name: '测试Agent',
      description: 'Agent描述',
      avatar: null,
      status: 'DRAFT',
      config: null,
      groupId: 1,
      groupName: '默认分组',
      skills: [
        {
          id: 9,
          name: '需求分析',
          description: '分析需求',
          type: 'SEQUENCE',
          sortOrder: 1,
          createdAt: '2026-06-05'
        }
      ],
      createdAt: '2026-06-05',
      updatedAt: '2026-06-05'
    }
  }))
  vi.mocked(agentApi.getSkillDetail).mockResolvedValue(response({
    code: 200,
    message: 'ok',
    data: {
      id: 9,
      agentId: 3,
      agentName: '测试Agent',
      name: '需求分析',
      description: '分析需求',
      type: 'SEQUENCE',
      config: '{}',
      sortOrder: 1,
      steps: [],
      createdAt: '2026-06-05',
      updatedAt: '2026-06-05'
    }
  }))

  return mount(AgentDetailView, {
    global: {
      plugins: [pinia],
      stubs: {
        'router-link': true,
        NIcon: true,
        NSpin: true,
        InkEmptyState: true,
        NButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
        NTag: { template: '<span><slot /></span>' },
        NModal: true,
        NForm: true,
        NFormItem: true,
        NInput: true,
        NSelect: true,
        NInputNumber: true,
        NSpace: true,
        NDynamicInput: true,
        NPopconfirm: true,
        AgentFormModal: true,
        AgentPermissionModal: true
      }
    }
  })
}

function response<T>(data: T): AxiosResponse<T> {
  return {
    data,
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: {} as never }
  }
}

describe('AgentDetailView skill permissions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('hides skill management controls without skill:manage', async () => {
    const wrapper = mountView(['agent:read'])
    await new Promise(resolve => setTimeout(resolve, 0))
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(wrapper.text()).not.toContain('新增能力')
    expect(wrapper.text()).not.toContain('编辑能力')
    expect(wrapper.text()).not.toContain('删除能力')
  })

  it('shows skill management controls with skill:manage', async () => {
    const wrapper = mountView(['agent:read', 'skill:manage'], { canManage: true })
    await new Promise(resolve => setTimeout(resolve, 0))
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(wrapper.text()).toContain('新增能力')
    expect(wrapper.text()).toContain('编辑能力')
    expect(wrapper.text()).toContain('删除能力')
  })

  it('hides skill management controls when user has global skill permission but cannot manage this agent', async () => {
    const wrapper = mountView(['agent:read', 'skill:manage'], { canManage: false })
    await new Promise(resolve => setTimeout(resolve, 0))
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(wrapper.text()).not.toContain('新增能力')
    expect(wrapper.text()).not.toContain('编辑能力')
    expect(wrapper.text()).not.toContain('删除能力')
  })

  it('shows permission management action only for agent managers', async () => {
    const manager = mountView(['agent:update'])
    await new Promise(resolve => setTimeout(resolve, 0))
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(manager.text()).toContain('授权')

    const reader = mountView(['agent:read'])
    await new Promise(resolve => setTimeout(resolve, 0))
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(reader.text()).not.toContain('授权')
  })

  it('shows copy edit action for users with copy access but without manage access', async () => {
    const wrapper = mountView(['agent:read'], { canCopy: true, canManage: false })
    await new Promise(resolve => setTimeout(resolve, 0))
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(wrapper.text()).toContain('复制编辑')
    expect(wrapper.text()).not.toContain('授权')
    expect(wrapper.text()).not.toContain('删除')
  })
})
