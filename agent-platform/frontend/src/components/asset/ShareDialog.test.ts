import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ShareDialog from './ShareDialog.vue'
import { memberApi, projectApi } from '@/api/assets'
import { adminApi, type UserVO } from '@/api/admin'
import type { AxiosResponse } from 'axios'
import type { MemberVO } from '@/types/asset'

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
  memberApi: { list: vi.fn(), invite: vi.fn(), changeRole: vi.fn(), remove: vi.fn() },
  projectApi: { transfer: vi.fn() }
}))

vi.mock('@/api/admin', () => ({
  adminApi: { listUsers: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function mkUser(id: number, username: string): UserVO {
  return {
    id,
    username,
    name: username,
    primaryDepartmentName: null,
    email: null,
    avatar: null,
    status: 'ACTIVE',
    lastLoginAt: null,
    createdAt: '2026-08-05',
    roles: [],
    permissions: []
  }
}

function mkMember(userId: number, role: 'OWNER' | 'EDITOR' | 'VIEWER'): MemberVO {
  return { userId, role, isOwner: role === 'OWNER', grantedBy: 1, grantedAt: '2026-08-05' }
}

function mountDialog() {
  return mount(ShareDialog, {
    props: { show: true, projectId: 7, projectName: '测试项目' }
  })
}

async function settle() {
  // 让 watch immediate 触发的 loadAll + 后续 reload 跑完
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

describe('ShareDialog (S9-9b 分享弹窗)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(adminApi.listUsers).mockResolvedValue(
      response({
        code: 200,
        message: 'ok',
        data: {
          records: [mkUser(1, 'owner'), mkUser(2, 'editor'), mkUser(3, 'viewer'), mkUser(4, 'outsider')],
          total: 4,
          page: 1,
          size: 200
        }
      })
    )
    vi.mocked(memberApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [mkMember(1, 'OWNER'), mkMember(2, 'EDITOR')] })
    )
    vi.mocked(memberApi.invite).mockResolvedValue(response({ code: 200, message: 'ok', data: mkMember(3, 'VIEWER') }))
    vi.mocked(memberApi.changeRole).mockResolvedValue(response({ code: 200, message: 'ok', data: undefined as never }))
    vi.mocked(memberApi.remove).mockResolvedValue(response({ code: 200, message: 'ok', data: undefined as never }))
    vi.mocked(projectApi.transfer).mockResolvedValue(response({ code: 200, message: 'ok', data: undefined as never }))
  })

  it('候选用户排除已成员', async () => {
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as { rows: { userId: number }[] }
    expect(vm.rows.map((r) => r.userId).sort()).toEqual([1, 2])
  })

  it('邀请选中用户逐个 invite + 重载（L1 前置：加入后可见）', async () => {
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as {
      selectedUserIds: number[]
      inviteSelected: () => Promise<void>
    }
    vm.selectedUserIds = [3, 4]
    await vm.inviteSelected()

    // 默认角色 VIEWER（inviteRole ref 初始值）
    expect(memberApi.invite).toHaveBeenCalledWith(7, { userId: 3, role: 'VIEWER' })
    expect(memberApi.invite).toHaveBeenCalledWith(7, { userId: 4, role: 'VIEWER' })
    // reload 后 memberApi.list 至少再被调一次
    expect(vi.mocked(memberApi.list).mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(wrapper.emitted('changed')).toBeTruthy()
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
