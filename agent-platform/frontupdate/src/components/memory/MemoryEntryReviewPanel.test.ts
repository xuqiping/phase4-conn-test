import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import MemoryEntryReviewPanel from './MemoryEntryReviewPanel.vue'
import { memoryApi, type MemoryGenMatrixItemVO, type MemoryProjectEntryVO } from '@/api/memory'
import type { AxiosResponse } from 'axios'

// 稳定单例 message/dialog（组件 setup 捕获 = 测试断言同一实例）
const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const dialogMock = { warning: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return {
    ...actual,
    useMessage: () => messageMock,
    useDialog: () => dialogMock
  }
})

vi.mock('@/api/memory', () => ({
  memoryApi: {
    getGenMatrix: vi.fn(),
    listEntries: vi.fn(),
    reviewEntry: vi.fn(),
    withdrawEntry: vi.fn()
  }
}))

// 当前登录用户 id=42（用于「作者可撤回」判定）
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ userInfo: { id: 42, username: 'me' } })
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function mkProject(projectId: number, role: 'OWNER' | 'ADMIN' | 'MEMBER'): MemoryGenMatrixItemVO {
  return {
    projectId,
    projectName: `项目${projectId}`,
    role,
    ownerEnabled: true,
    memberEnabled: true,
    effective: true
  }
}

function mkEntry(id: number, overrides: Partial<MemoryProjectEntryVO> = {}): MemoryProjectEntryVO {
  return {
    id,
    projectId: 1,
    authorUserId: 7,
    authorName: '张三',
    l1Summary: `摘要${id}`,
    l2Detail: null,
    confidence: 0.65,
    status: 'PENDING_REVIEW',
    contentType: 'TEXT',
    ruleText: '涉及 SeedDance',
    createdAt: '2026-08-08T10:00:00Z',
    ...overrides
  }
}

function apiOk<T>(data: T) {
  return response({ code: 200, message: 'ok', data })
}

async function settle() {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

describe('MemoryEntryReviewPanel（二期 P1 · FR-005 收录审核）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('默认选中首个 owner/admin 项目，按 PENDING_REVIEW 过滤拉取条目', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([mkProject(1, 'MEMBER'), mkProject(2, 'OWNER')]))
    vi.mocked(memoryApi.listEntries).mockResolvedValue(apiOk([mkEntry(11)]))

    const wrapper = mount(MemoryEntryReviewPanel)
    await settle()

    expect(memoryApi.listEntries).toHaveBeenCalledWith(2, 'PENDING_REVIEW')
    const vm = wrapper.vm as unknown as { isManager: boolean; entries: MemoryProjectEntryVO[] }
    expect(vm.isManager).toBe(true)
    expect(vm.entries.map(e => e.id)).toEqual([11])
  })

  it('owner 审核收录：approve 后待审核列表移除该条', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([mkProject(1, 'OWNER')]))
    vi.mocked(memoryApi.listEntries).mockResolvedValue(apiOk([mkEntry(11), mkEntry(12)]))
    vi.mocked(memoryApi.reviewEntry).mockResolvedValue(apiOk(undefined as never))

    const wrapper = mount(MemoryEntryReviewPanel)
    await settle()

    const vm = wrapper.vm as unknown as {
      entries: MemoryProjectEntryVO[]
      review: (e: MemoryProjectEntryVO, a: 'approve' | 'reject') => Promise<void>
    }
    await vm.review(vm.entries[0], 'approve')

    expect(memoryApi.reviewEntry).toHaveBeenCalledWith(11, 'approve')
    expect(vm.entries.map(e => e.id)).toEqual([12])
    expect(messageMock.success).toHaveBeenCalledWith('已收录')
  })

  it('弃走 dialog 确认；确认后调用 reject 且提示负例反哺', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([mkProject(1, 'ADMIN')]))
    vi.mocked(memoryApi.listEntries).mockResolvedValue(apiOk([mkEntry(11)]))
    vi.mocked(memoryApi.reviewEntry).mockResolvedValue(apiOk(undefined as never))
    dialogMock.warning.mockImplementation((opts: { onPositiveClick?: () => unknown }) => {
      opts.onPositiveClick?.()
    })

    const wrapper = mount(MemoryEntryReviewPanel)
    await settle()

    const vm = wrapper.vm as unknown as {
      entries: MemoryProjectEntryVO[]
      confirmReject: (e: MemoryProjectEntryVO) => void
    }
    vm.confirmReject(vm.entries[0])
    await settle()

    expect(dialogMock.warning).toHaveBeenCalled()
    expect(memoryApi.reviewEntry).toHaveBeenCalledWith(11, 'reject')
    expect(messageMock.success).toHaveBeenCalledWith('已弃（摘要已反哺为规则负例）')
    expect(vm.entries).toEqual([])
  })

  it('成员角色不渲染审核按钮；作者本人条目渲染撤回', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([mkProject(1, 'MEMBER')]))
    vi.mocked(memoryApi.listEntries).mockResolvedValue(
      apiOk([mkEntry(11, { authorUserId: 42 }), mkEntry(12, { authorUserId: 7 })])
    )

    const wrapper = mount(MemoryEntryReviewPanel)
    await settle()

    const vm = wrapper.vm as unknown as {
      isManager: boolean
      entries: MemoryProjectEntryVO[]
      showActions: (e: MemoryProjectEntryVO) => boolean
    }
    expect(vm.isManager).toBe(false)
    // 本人条目（id=11）有动作区（撤回）；他人条目（id=12）无动作
    expect(vm.showActions(vm.entries[0])).toBe(true)
    expect(vm.showActions(vm.entries[1])).toBe(false)
    // 审核「收录」按钮不渲染（顶部说明文案含「收录」字样，只能查按钮文本）
    const buttonTexts = wrapper.findAll('button').map(b => b.text())
    expect(buttonTexts).not.toContain('收录')
  })

  it('撤回走 dialog 确认，确认后调用 withdrawEntry 并移除', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([mkProject(1, 'MEMBER')]))
    vi.mocked(memoryApi.listEntries).mockResolvedValue(apiOk([mkEntry(11, { authorUserId: 42 })]))
    vi.mocked(memoryApi.withdrawEntry).mockResolvedValue(apiOk(undefined as never))
    dialogMock.warning.mockImplementation((opts: { onPositiveClick?: () => unknown }) => {
      opts.onPositiveClick?.()
    })

    const wrapper = mount(MemoryEntryReviewPanel)
    await settle()

    const vm = wrapper.vm as unknown as {
      entries: MemoryProjectEntryVO[]
      confirmWithdraw: (e: MemoryProjectEntryVO) => void
    }
    vm.confirmWithdraw(vm.entries[0])
    await settle()

    expect(memoryApi.withdrawEntry).toHaveBeenCalledWith(11)
    expect(vm.entries).toEqual([])
    expect(messageMock.success).toHaveBeenCalledWith('已撤回')
  })
})
