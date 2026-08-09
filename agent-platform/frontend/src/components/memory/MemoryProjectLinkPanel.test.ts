import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import MemoryProjectLinkPanel from './MemoryProjectLinkPanel.vue'
import { memoryApi, type MemoryGenMatrixItemVO, type MemoryProjectLinkVO } from '@/api/memory'
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
    listMyLinks: vi.fn(),
    listMyUserGrants: vi.fn(),
    listPoolProjects: vi.fn(),
    createLink: vi.fn(),
    approveLink: vi.fn(),
    rejectLink: vi.fn(),
    revokeLink: vi.fn()
  }
}))

// 二期 P1：useAuthStore 需 pinia；测试桩直接返回 userInfo.id 供「我被授权的」拆分
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ userInfo: { id: 100 } })
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

function mkLink(id: number, parentId: number, childId: number, status: MemoryProjectLinkVO['status']): MemoryProjectLinkVO {
  return {
    id,
    parentProjectId: parentId,
    parentProjectName: `项目${parentId}`,
    childProjectId: childId,
    childProjectName: `项目${childId}`,
    grantedBy: 100,
    grantedByName: '张三',
    approvedBy: null,
    approvedByName: null,
    status,
    createdAt: '2026-08-08T10:00:00Z',
    approvedAt: null
  }
}

function apiOk<T>(data: T) {
  return response({ code: 200, message: 'ok', data })
}

async function settle() {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

describe('MemoryProjectLinkPanel（二期 P2 · FR-101 项目授权）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // 二期 P1：load() 现多查 listMyUserGrants；默认空（现有用例不涉及个人授权）
    vi.mocked(memoryApi.listMyUserGrants).mockResolvedValue(apiOk([]))
    // 三期：load() 增查公共池候选；默认空（现有用例不涉及 pool）
    vi.mocked(memoryApi.listPoolProjects).mockResolvedValue(apiOk([]))
  })

  it('按「我管的侧」拆分两栏：child 我管=授权出去，parent 我管=待我审批', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([mkProject(1, 'OWNER'), mkProject(2, 'ADMIN')]))
    vi.mocked(memoryApi.listMyLinks).mockResolvedValue(apiOk([
      mkLink(11, 3, 1, 'ACTIVE'),    // child=项目1（我 owner）→ 我授权出去的
      mkLink(12, 2, 5, 'PENDING'),   // parent=项目2（我 admin）→ 待我审批
      mkLink(13, 8, 9, 'ACTIVE')     // 两侧都不我管（理论不返，前端兜底滤掉）
    ]))

    const wrapper = mount(MemoryProjectLinkPanel)
    await settle()

    const vm = wrapper.vm as unknown as {
      outgoing: MemoryProjectLinkVO[]
      incoming: MemoryProjectLinkVO[]
    }
    expect(vm.outgoing.map(l => l.id)).toEqual([11])
    expect(vm.incoming.map(l => l.id)).toEqual([12])
  })

  it('发起授权：child 选项仅 OWNER 项目，parent 选项排除已选 child', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([
      mkProject(1, 'OWNER'), mkProject(2, 'ADMIN'), mkProject(3, 'MEMBER')
    ]))
    vi.mocked(memoryApi.listMyLinks).mockResolvedValue(apiOk([]))
    vi.mocked(memoryApi.createLink).mockResolvedValue(apiOk(mkLink(11, 2, 1, 'PENDING')))

    const wrapper = mount(MemoryProjectLinkPanel)
    await settle()

    const vm = wrapper.vm as unknown as {
      ownedProjectOptions: { label: string; value: number }[]
      grantParentOptions: { label: string; value: number }[]
      grantChildId: number | null
      grantParentId: number | null
      grant: () => Promise<void>
    }
    // child 候选=仅 OWNER（后端发起权=child owner）
    expect(vm.ownedProjectOptions.map(o => o.value)).toEqual([1])
    vm.grantChildId = 1
    vm.grantParentId = 2
    await vm.grant()

    expect(memoryApi.createLink).toHaveBeenCalledWith(1, 2)
    expect(messageMock.success).toHaveBeenCalledWith('已发起，待对方审批')
  })

  it('三期 G1：被授权方下拉纳入公共池项目（标「公共池」，去重，排除已选 child）', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([mkProject(1, 'OWNER')]))
    vi.mocked(memoryApi.listMyLinks).mockResolvedValue(apiOk([]))
    // 公共池含 id=1（与本人项目重复，应去重）+ id=50（新项目）
    vi.mocked(memoryApi.listPoolProjects).mockResolvedValue(apiOk([
      { id: 1, name: '项目1' }, { id: 50, name: '测试' }
    ]))

    const wrapper = mount(MemoryProjectLinkPanel)
    await settle()

    const vm = wrapper.vm as unknown as {
      grantParentOptions: { label: string; value: number }[]
      grantChildId: number | null
    }
    // 去重：项目1 已在 gen 矩阵，不重复；公共池 50 入选项带后缀
    expect(vm.grantParentOptions).toEqual([
      { label: '项目1', value: 1 },
      { label: '测试（公共池）', value: 50 }
    ])
    // 选 child=1 后，被授权方排除 child 防自环
    vm.grantChildId = 1
    await settle()
    expect(vm.grantParentOptions.map(o => o.value)).toEqual([50])
  })

  it('parent 侧 PENDING：通过调用 approveLink 并刷新', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([mkProject(2, 'OWNER')]))
    vi.mocked(memoryApi.listMyLinks).mockResolvedValue(apiOk([mkLink(12, 2, 5, 'PENDING')]))
    vi.mocked(memoryApi.approveLink).mockResolvedValue(apiOk(undefined as never))

    const wrapper = mount(MemoryProjectLinkPanel)
    await settle()

    const vm = wrapper.vm as unknown as {
      incoming: MemoryProjectLinkVO[]
      approve: (l: MemoryProjectLinkVO) => Promise<void>
    }
    await vm.approve(vm.incoming[0])

    expect(memoryApi.approveLink).toHaveBeenCalledWith(12)
  })

  it('拒绝走 dialog 确认（30 天防刷提示）', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([mkProject(2, 'OWNER')]))
    vi.mocked(memoryApi.listMyLinks).mockResolvedValue(apiOk([mkLink(12, 2, 5, 'PENDING')]))
    vi.mocked(memoryApi.rejectLink).mockResolvedValue(apiOk(undefined as never))
    dialogMock.warning.mockImplementation((opts: { onPositiveClick?: () => unknown }) => {
      opts.onPositiveClick?.()
    })

    const wrapper = mount(MemoryProjectLinkPanel)
    await settle()

    const vm = wrapper.vm as unknown as {
      incoming: MemoryProjectLinkVO[]
      reject: (l: MemoryProjectLinkVO) => void
    }
    vm.reject(vm.incoming[0])
    await settle()

    expect(dialogMock.warning).toHaveBeenCalled()
    expect(memoryApi.rejectLink).toHaveBeenCalledWith(12)
  })

  it('child 侧 ACTIVE：撤销走 dialog 确认后调用 revokeLink', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([mkProject(1, 'OWNER')]))
    vi.mocked(memoryApi.listMyLinks).mockResolvedValue(apiOk([mkLink(11, 3, 1, 'ACTIVE')]))
    vi.mocked(memoryApi.revokeLink).mockResolvedValue(apiOk(undefined as never))
    dialogMock.warning.mockImplementation((opts: { onPositiveClick?: () => unknown }) => {
      opts.onPositiveClick?.()
    })

    const wrapper = mount(MemoryProjectLinkPanel)
    await settle()

    const vm = wrapper.vm as unknown as {
      outgoing: MemoryProjectLinkVO[]
      revoke: (l: MemoryProjectLinkVO, hint: string) => void
    }
    vm.revoke(vm.outgoing[0], 'hint')
    await settle()

    expect(memoryApi.revokeLink).toHaveBeenCalledWith(11)
  })
})
