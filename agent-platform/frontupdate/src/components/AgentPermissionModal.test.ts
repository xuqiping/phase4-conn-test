import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import AgentPermissionModal from './AgentPermissionModal.vue'
import { agentApi } from '@/api/agent'
import { adminApi } from '@/api/admin'
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
    listAgentPermissions: vi.fn(),
    saveAgentPermissions: vi.fn()
  }
}))

vi.mock('@/api/admin', () => ({
  adminApi: {
    listUsers: vi.fn()
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

function mountModal() {
  return mount(AgentPermissionModal, {
    props: {
      show: true,
      agentId: 3
    },
    global: {
      stubs: {
        NModal: { template: '<div><slot /><slot name="action" /></div>' },
        NDataTable: true,
        NButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
        NSelect: true,
        NSpace: { template: '<div><slot /></div>' },
        NSwitch: true,
        NTag: { template: '<span><slot /></span>' },
        NEmpty: true
      }
    }
  })
}

describe('AgentPermissionModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(adminApi.listUsers).mockResolvedValue(response({
      code: 200,
      message: 'ok',
      data: {
        records: [
          {
            id: 7,
            username: 'alice',
            email: null,
            avatar: null,
            status: 'ACTIVE',
            lastLoginAt: null,
            createdAt: '2026-06-12',
            roles: [],
            permissions: []
          },
          {
            id: 8,
            username: 'bob',
            email: null,
            avatar: null,
            status: 'ACTIVE',
            lastLoginAt: null,
            createdAt: '2026-06-12',
            roles: [],
            permissions: []
          },
          {
            id: 9,
            username: 'cindy',
            email: null,
            avatar: null,
            status: 'ACTIVE',
            lastLoginAt: null,
            createdAt: '2026-06-12',
            roles: [],
            permissions: []
          }
        ],
        total: 3,
        page: 1,
        size: 100
      }
    }))
    vi.mocked(agentApi.listAgentPermissions).mockResolvedValue(response({
      code: 200,
      message: 'ok',
      data: [
        {
          id: 11,
          agentId: 3,
          userId: 7,
          username: 'alice',
          canUse: true,
          canReadPrompt: false,
          canCopy: true,
          createdAt: '2026-06-12',
          updatedAt: '2026-06-12'
        }
      ]
    }))
    vi.mocked(agentApi.saveAgentPermissions).mockResolvedValue(response({
      code: 200,
      message: 'ok',
      data: []
    }))
  })

  it('loads users and current permissions when opened', async () => {
    mountModal()
    await Promise.resolve()
    await Promise.resolve()

    expect(adminApi.listUsers).toHaveBeenCalledWith(1, 100)
    expect(agentApi.listAgentPermissions).toHaveBeenCalledWith(3)
  })

  it('saves permissions and implies use for prompt-read or copy permission', async () => {
    const wrapper = mountModal()
    await Promise.resolve()
    await Promise.resolve()

    const vm = wrapper.vm as unknown as {
      rows: Array<{ targetUserId: number; canUse: boolean; canReadPrompt: boolean; canCopy: boolean }>
      save: () => Promise<void>
    }
    vm.rows[0].canUse = false
    vm.rows[0].canReadPrompt = true
    await vm.save()

    expect(agentApi.saveAgentPermissions).toHaveBeenCalledWith(3, [
      {
        userId: 7,
        canUse: true,
        canReadPrompt: true,
        canCopy: true
      }
    ])
    expect(wrapper.emitted('saved')).toBeTruthy()
    expect(wrapper.emitted('update:show')?.[0]).toEqual([false])
  })

  it('adds permissions for multiple selected users at once', async () => {
    const wrapper = mountModal()
    await Promise.resolve()
    await Promise.resolve()

    const vm = wrapper.vm as unknown as {
      selectedUserIds: number[]
      rows: Array<{ targetUserId: number; targetUsername: string; canUse: boolean }>
      addPermission: () => void
    }
    vm.selectedUserIds = [8, 9]
    vm.addPermission()

    expect(vm.rows.map(row => row.targetUserId)).toEqual([7, 8, 9])
    expect(vm.rows.find(row => row.targetUserId === 8)).toMatchObject({
      targetUsername: 'bob',
      canUse: true
    })
    expect(vm.rows.find(row => row.targetUserId === 9)).toMatchObject({
      targetUsername: 'cindy',
      canUse: true
    })
    expect(vm.selectedUserIds).toEqual([])
  })
})
