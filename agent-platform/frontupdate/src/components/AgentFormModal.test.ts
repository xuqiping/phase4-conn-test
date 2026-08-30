import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import AgentFormModal from './AgentFormModal.vue'
import { agentApi } from '@/api/agent'
import type { AxiosResponse } from 'axios'

vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return {
    ...actual,
    useMessage: () => ({
      success: vi.fn(),
      error: vi.fn()
    })
  }
})

vi.mock('@/api/agent', () => ({
  agentApi: {
    createAgent: vi.fn(),
    updateAgent: vi.fn(),
    copyAgent: vi.fn()
  }
}))

function response<T>(data: T): AxiosResponse<T> {
  return {
    data,
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: {} as never }
  }
}

function mountModal(saveMode: 'update' | 'copy' = 'update') {
  return mount(AgentFormModal, {
    props: {
      show: true,
      saveMode,
      groups: [{ id: 2, name: 'Default', icon: null, description: null, sortOrder: 1, agentCount: 1, createdAt: '2026-06-12' }],
      editData: {
        id: 3,
        name: 'Source Agent',
        description: 'source',
        avatar: null,
        groupId: 2
      }
    },
    global: {
      stubs: {
        NModal: { template: '<div><slot /><slot name="action" /></div>' },
        NForm: {
          template: '<form><slot /></form>',
          methods: { validate: vi.fn().mockResolvedValue(undefined) }
        },
        NFormItem: { template: '<div><slot /></div>' },
        NInput: true,
        NSelect: true,
        NButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' }
      }
    }
  })
}

describe('AgentFormModal save mode', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentApi.updateAgent).mockResolvedValue(response({
      code: 200,
      message: 'ok',
      data: {
        id: 3,
        name: 'Source Agent',
        description: 'source',
        avatar: null,
        status: 'DRAFT',
        groupId: 2,
        groupName: 'Default',
        skillCount: 0,
        createdAt: '2026-06-12'
      }
    }))
    vi.mocked(agentApi.copyAgent).mockResolvedValue(response({
      code: 200,
      message: 'ok',
      data: {
        id: 8,
        name: 'Source Agent',
        description: 'source',
        avatar: null,
        status: 'DRAFT',
        config: null,
        groupId: 2,
        groupName: 'Default',
        skills: [],
        createdAt: '2026-06-12',
        updatedAt: '2026-06-12'
      }
    }))
  })

  it('updates the original agent by default', async () => {
    const wrapper = mountModal('update')
    await Promise.resolve()

    await (wrapper.vm as unknown as { handleSubmit: () => Promise<void> }).handleSubmit()

    expect(agentApi.updateAgent).toHaveBeenCalledWith(3, {
      name: 'Source Agent',
      description: 'source',
      avatar: undefined,
      groupId: 2
    })
    expect(agentApi.copyAgent).not.toHaveBeenCalled()
  })

  it('copies the agent instead of updating when save mode is copy', async () => {
    const wrapper = mountModal('copy')
    await Promise.resolve()

    await (wrapper.vm as unknown as { handleSubmit: () => Promise<void> }).handleSubmit()

    expect(agentApi.copyAgent).toHaveBeenCalledWith(3, {
      name: 'Source Agent',
      description: 'source',
      avatar: undefined,
      groupId: 2
    })
    expect(agentApi.updateAgent).not.toHaveBeenCalled()
    expect(wrapper.emitted('copied')).toBeTruthy()
  })
})
