import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { NSelect } from 'naive-ui'
import type { AxiosResponse } from 'axios'
import ShareDialog from './ShareDialog.vue'
import { memberApi, projectApi } from '@/api/assets'
import { adminApi } from '@/api/admin'
import type { MemberCandidateVO, MemberVO } from '@/types/asset'

// 稳定单例 dialog（组件 setup 捕获 = 测试断言同一实例）
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

vi.mock('@/api/assets', () => ({
  memberApi: {
    list: vi.fn(),
    searchCandidates: vi.fn(),
    invite: vi.fn(),
    changeRole: vi.fn(),
    remove: vi.fn()
  },
  projectApi: { transfer: vi.fn() }
}))

vi.mock('@/api/admin', () => ({
  adminApi: { listUsers: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function mkMember(userId: number, username: string, role: 'OWNER' | 'EDITOR' | 'VIEWER'): MemberVO {
  return { userId, username, role, isOwner: role === 'OWNER', grantedBy: 1, grantedAt: '2026-08-05' }
}

function candidate(id: number, username: string): MemberCandidateVO {
  return { id, username }
}

function memberListResponse(members: MemberVO[]) {
  return response({ code: 200, message: 'ok', data: members })
}

function mountDialog() {
  return mount(ShareDialog, {
    props: { show: true, projectId: 7, projectName: '测试项目' },
    global: { stubs: { teleport: true } }
  })
}

async function settle() {
  await flushPromises()
}

describe('ShareDialog (资产成员安全分享)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(memberApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [mkMember(1, 'owner', 'OWNER'), mkMember(2, 'editor', 'EDITOR')] })
    )
    vi.mocked(memberApi.searchCandidates).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [candidate(3, 'viewer'), candidate(4, 'outsider')] })
    )
    vi.mocked(memberApi.invite).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkMember(3, 'viewer', 'VIEWER') })
    )
    vi.mocked(memberApi.changeRole).mockResolvedValue(response({ code: 200, message: 'ok', data: undefined as never }))
    vi.mocked(memberApi.remove).mockResolvedValue(response({ code: 200, message: 'ok', data: undefined as never }))
    vi.mocked(projectApi.transfer).mockResolvedValue(response({ code: 200, message: 'ok', data: undefined as never }))
  })

  it('打开时只加载资产成员，并直接显示 MemberVO.username', async () => {
    const wrapper = mountDialog()
    await settle()

    const vm = wrapper.vm as unknown as { rows: { userId: number; username: string }[] }
    expect(memberApi.list).toHaveBeenCalledOnce()
    expect(memberApi.list).toHaveBeenCalledWith(7)
    expect(adminApi.listUsers).not.toHaveBeenCalled()
    expect(vm.rows).toEqual([
      expect.objectContaining({ userId: 1, username: 'owner' }),
      expect.objectContaining({ userId: 2, username: 'editor' })
    ])
  })

  it('远程选择器可搜索，并提供可读标签与加载态', async () => {
    const wrapper = mountDialog()
    await settle()

    const selects = wrapper.findAllComponents(NSelect)
    const userSelect = selects[0]
    expect(userSelect.props('remote')).toBe(true)
    expect(userSelect.props('filterable')).toBe(true)
    expect(userSelect.props('loading')).toBe(false)
    expect(userSelect.attributes('aria-label')).toBe('搜索可邀请的项目成员')
    expect(userSelect.props('placeholder')).toBe('输入用户名搜索')
  })

  // 2x#5：打开即以空关键词载候选（开箱即见 ≤50 人），状态行给上限提示
  it('打开弹窗即以空关键词请求候选并展示', async () => {
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as { candidateOptions: { label: string; value: number }[] }

    expect(memberApi.searchCandidates).toHaveBeenCalledWith(7, '')
    expect(vm.candidateOptions).toEqual([
      { label: 'viewer', value: 3 },
      { label: 'outsider', value: 4 }
    ])
    expect(wrapper.text()).toContain('输入用户名筛选，最多展示 50 人')
  })

  it('通过远程选择器 search 事件按关键词搜索最小候选', async () => {
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as {
      candidateOptions: { label: string; value: number }[]
    }
    const userSelect = wrapper.findAllComponents(NSelect)[0]

    userSelect.vm.$emit('search', '  view  ')
    await settle()

    expect(memberApi.searchCandidates).toHaveBeenCalledWith(7, 'view')
    expect(vm.candidateOptions).toEqual([
      { label: 'viewer', value: 3 },
      { label: 'outsider', value: 4 }
    ])
  })

  // 2x#5：清空关键词=发空关键词请求恢复全量候选，而非空白
  it('清空关键词重新请求全量候选', async () => {
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as {
      searchCandidates: (keyword: string) => Promise<void>
      candidateOptions: { label: string; value: number }[]
    }

    await vm.searchCandidates('viewer')
    vi.mocked(memberApi.searchCandidates).mockClear()
    await vm.searchCandidates('   ')

    expect(memberApi.searchCandidates).toHaveBeenCalledWith(7, '')
    expect(vm.candidateOptions).toEqual([
      { label: 'viewer', value: 3 },
      { label: 'outsider', value: 4 }
    ])
  })

  it('成员加载失败后仍可搜索并获得候选', async () => {
    vi.mocked(memberApi.list).mockRejectedValueOnce(new Error('members unavailable'))
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as {
      searchCandidates: (keyword: string) => Promise<void>
      candidateOptions: { label: string; value: number }[]
    }

    await vm.searchCandidates('viewer')

    expect(vm.candidateOptions).toEqual([
      { label: 'viewer', value: 3 },
      { label: 'outsider', value: 4 }
    ])
    expect(wrapper.text()).toContain('成员列表加载失败')
  })

  it('慢 A 响应晚于快 B 时不能覆盖 B 成员', async () => {
    const slowA = deferred<ReturnType<typeof memberListResponse>>()
    vi.mocked(memberApi.list)
      .mockReset()
      .mockImplementationOnce(() => slowA.promise)
      .mockResolvedValueOnce(memberListResponse([mkMember(8, 'project-b-owner', 'OWNER')]))
    const wrapper = mountDialog()
    await Promise.resolve()

    await wrapper.setProps({ projectId: 8, projectName: '项目 B' })
    await settle()
    const vm = wrapper.vm as unknown as { rows: { username: string }[] }
    expect(vm.rows.map((row) => row.username)).toEqual(['project-b-owner'])

    slowA.resolve(memberListResponse([mkMember(1, 'late-project-a-owner', 'OWNER')]))
    await settle()
    expect(vm.rows.map((row) => row.username)).toEqual(['project-b-owner'])
  })

  it('从已有成员的 A 切到加载失败的 B 时立即清空旧 rows', async () => {
    const failingB = deferred<ReturnType<typeof memberListResponse>>()
    vi.mocked(memberApi.list)
      .mockReset()
      .mockResolvedValueOnce(memberListResponse([mkMember(1, 'project-a-owner', 'OWNER')]))
      .mockImplementationOnce(() => failingB.promise)
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as { rows: { username: string }[] }
    expect(vm.rows.map((row) => row.username)).toEqual(['project-a-owner'])

    await wrapper.setProps({ projectId: 8, projectName: '项目 B' })
    expect(memberApi.list).toHaveBeenLastCalledWith(8)
    expect(vm.rows).toEqual([])

    failingB.reject(new Error('project B unavailable'))
    await settle()
    expect(wrapper.text()).toContain('成员列表加载失败')
  })

  it('切换项目时清空已选择的候选用户', async () => {
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as { selectedUserIds: number[] }
    vm.selectedUserIds = [3]

    await wrapper.setProps({ projectId: 8, projectName: '项目 B' })

    expect(vm.selectedUserIds).toEqual([])
  })

  it('关闭并重新打开时清空已选择的候选用户', async () => {
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as { selectedUserIds: number[] }
    vm.selectedUserIds = [3]

    await wrapper.setProps({ show: false })
    expect(vm.selectedUserIds).toEqual([])
    await wrapper.setProps({ show: true })
    expect(vm.selectedUserIds).toEqual([])
  })

  it('候选搜索失败显示独立错误且保留成员表', async () => {
    const wrapper = mountDialog()
    await settle()
    vi.mocked(memberApi.searchCandidates).mockRejectedValueOnce(new Error('search unavailable'))
    const vm = wrapper.vm as unknown as {
      searchCandidates: (keyword: string) => Promise<void>
      rows: { username: string }[]
    }

    await vm.searchCandidates('viewer')

    expect(vm.rows.map((row) => row.username)).toEqual(['owner', 'editor'])
    expect(wrapper.text()).toContain('候选成员搜索失败，请重试')
  })

  it('邀请成功后清空选择与远程搜索状态、刷新成员并通知父级', async () => {
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as {
      selectedUserIds: number[]
      candidateKeyword: string
      candidateOptions: { label: string; value: number }[]
      searchCandidates: (keyword: string) => Promise<void>
      inviteSelected: () => Promise<void>
    }
    await vm.searchCandidates('viewer')
    vm.selectedUserIds = [3, 4]
    await vm.inviteSelected()

    expect(memberApi.invite).toHaveBeenNthCalledWith(1, 7, { userId: 3, role: 'VIEWER' })
    expect(memberApi.invite).toHaveBeenNthCalledWith(2, 7, { userId: 4, role: 'VIEWER' })
    expect(vm.selectedUserIds).toEqual([])
    expect(vm.candidateKeyword).toBe('')
    expect(vm.candidateOptions).toEqual([])
    expect(vi.mocked(memberApi.list).mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(wrapper.emitted('changed')).toBeTruthy()
  })

  it('邀请期间切换项目时不向新项目续发邀请，也不由旧操作刷新或通知新上下文', async () => {
    const firstInvite = deferred<ReturnType<typeof response<{ code: number; message: string; data: MemberVO }>>>()
    vi.mocked(memberApi.invite)
      .mockReset()
      .mockImplementationOnce(() => firstInvite.promise)
      .mockResolvedValue(response({ code: 200, message: 'ok', data: mkMember(4, 'outsider', 'VIEWER') }))
    const wrapper = mountDialog()
    await settle()
    vi.mocked(memberApi.list).mockClear()
    const vm = wrapper.vm as unknown as {
      selectedUserIds: number[]
      inviteSelected: () => Promise<void>
    }
    vm.selectedUserIds = [3, 4]

    const inviting = vm.inviteSelected()
    await Promise.resolve()
    expect(memberApi.invite).toHaveBeenCalledWith(7, { userId: 3, role: 'VIEWER' })
    await wrapper.setProps({ projectId: 8, projectName: '项目 B' })
    await settle()
    firstInvite.resolve(response({ code: 200, message: 'ok', data: mkMember(3, 'viewer', 'VIEWER') }))
    await inviting
    await settle()

    expect(memberApi.invite).toHaveBeenCalledTimes(1)
    expect(memberApi.list).toHaveBeenCalledTimes(1)
    expect(memberApi.list).toHaveBeenCalledWith(8)
    expect(wrapper.emitted('changed')).toBeFalsy()
  })

  it('改角色调 memberApi.changeRole', async () => {
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as { changeRole: (u: number, r: 'VIEWER' | 'EDITOR') => Promise<void> }
    await vm.changeRole(2, 'VIEWER')
    expect(memberApi.changeRole).toHaveBeenCalledWith(7, 2, { role: 'VIEWER' })
  })

  it('移除成员二次确认 → memberApi.remove（L1）', async () => {
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as { confirmRemove: (u: number, n: string) => void }
    vm.confirmRemove(2, 'editor')
    expect(dialogMock.warning).toHaveBeenCalled()
    const opts = dialogMock.warning.mock.calls[0][0] as { onPositiveClick: () => Promise<void> }
    await opts.onPositiveClick()
    expect(memberApi.remove).toHaveBeenCalledWith(7, 2)
    expect(wrapper.emitted('changed')).toBeTruthy()
  })

  it('转让 owner 二次确认 → projectApi.transfer + 关弹窗', async () => {
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as { confirmTransfer: (u: number, n: string) => void }
    vm.confirmTransfer(2, 'editor')
    expect(dialogMock.warning).toHaveBeenCalled()
    const opts = dialogMock.warning.mock.calls[0][0] as { onPositiveClick: () => Promise<void> }
    await opts.onPositiveClick()
    expect(projectApi.transfer).toHaveBeenCalledWith(7, { toUserId: 2 })
    expect(wrapper.emitted('changed')).toBeTruthy()
    expect(wrapper.emitted('update:show')).toBeTruthy()
  })
})
