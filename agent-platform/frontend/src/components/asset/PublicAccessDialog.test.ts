import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { NButton, NModal } from 'naive-ui'
import type { AxiosResponse } from 'axios'
import PublicAccessDialog from './PublicAccessDialog.vue'
import { publicPoolApi } from '@/api/assets'
import type { PublicAccessRequestVO, PublicAccessStatus } from '@/types/asset'

const messageMock = { success: vi.fn(), error: vi.fn() }

vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/assets', () => ({
  publicPoolApi: {
    listRequests: vi.fn(),
    decideRequest: vi.fn(),
    revokeApproval: vi.fn()
  }
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

function request(id: number, projectId: number, status: PublicAccessStatus, applicantId = id + 100): PublicAccessRequestVO {
  return {
    id,
    projectId,
    applicantId,
    status,
    createdAt: '2026-08-10T08:00:00Z',
    updatedAt: '2026-08-10T08:00:00Z'
  }
}

function listResponse(rows: PublicAccessRequestVO[]) {
  return response({ code: 200, message: 'ok', data: rows })
}

function mountDialog(projectId = 7, projectName = '镜头语言素材库') {
  return mount(PublicAccessDialog, {
    props: { show: true, projectId, projectName },
    global: { stubs: { teleport: true } }
  })
}

async function settle() {
  await flushPromises()
}

describe('PublicAccessDialog', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(publicPoolApi.listRequests).mockResolvedValue(listResponse([]))
    vi.mocked(publicPoolApi.decideRequest).mockResolvedValue(response({ code: 200, message: 'ok', data: undefined }))
    vi.mocked(publicPoolApi.revokeApproval).mockResolvedValue(response({ code: 200, message: 'ok', data: undefined }))
  })

  it('打开时加载申请，并为各状态提供正确中文状态与动作矩阵', async () => {
    vi.mocked(publicPoolApi.listRequests).mockResolvedValueOnce(listResponse([
      request(1, 7, 'PENDING', 21),
      request(2, 7, 'APPROVED', 22),
      request(3, 7, 'REJECTED', 23),
      request(4, 7, 'REVOKED', 24)
    ]))
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as {
      rows: Array<{ applicant: string; statusLabel: string; actions: string[] }>
    }

    expect(publicPoolApi.listRequests).toHaveBeenCalledWith(7)
    expect(vm.rows).toEqual([
      expect.objectContaining({ applicant: '用户 #21', statusLabel: '待审批', actions: ['approve', 'reject'] }),
      expect.objectContaining({ applicant: '用户 #22', statusLabel: '已批准', actions: ['revoke'] }),
      expect.objectContaining({ applicant: '用户 #23', statusLabel: '已拒绝', actions: [] }),
      expect.objectContaining({ applicant: '用户 #24', statusLabel: '已撤销', actions: [] })
    ])
    expect(wrapper.text()).toContain('申请时间')
    expect(wrapper.text()).toContain('批准')
    expect(wrapper.text()).toContain('拒绝')
    expect(wrapper.text()).toContain('撤销访问')
  })

  it('批准、拒绝和撤销分别调用正确 API，成功后刷新并通知变更', async () => {
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as {
      decide: (requestId: number, decision: 'APPROVED' | 'REJECTED') => Promise<void>
      revoke: (requestId: number) => Promise<void>
    }
    vi.mocked(publicPoolApi.listRequests).mockClear()

    await vm.decide(11, 'APPROVED')
    expect(publicPoolApi.decideRequest).toHaveBeenCalledWith(7, 11, { decision: 'APPROVED' })
    expect(publicPoolApi.listRequests).toHaveBeenCalledTimes(1)

    await vm.decide(12, 'REJECTED')
    expect(publicPoolApi.decideRequest).toHaveBeenCalledWith(7, 12, { decision: 'REJECTED' })
    expect(publicPoolApi.listRequests).toHaveBeenCalledTimes(2)

    await vm.revoke(13)
    expect(publicPoolApi.revokeApproval).toHaveBeenCalledWith(7, 13)
    expect(publicPoolApi.listRequests).toHaveBeenCalledTimes(3)
    expect(wrapper.emitted('changed')).toHaveLength(3)
    expect(messageMock.success).toHaveBeenCalledTimes(3)
  })

  it('通过真实批准按钮触发决定 API', async () => {
    vi.mocked(publicPoolApi.listRequests).mockResolvedValueOnce(listResponse([request(11, 7, 'PENDING')]))
    const wrapper = mountDialog()
    await settle()
    const approve = wrapper.findAllComponents(NButton).find((button) => button.text() === '批准')

    expect(approve).toBeDefined()
    await approve!.trigger('click')
    await settle()

    expect(publicPoolApi.decideRequest).toHaveBeenCalledWith(7, 11, { decision: 'APPROVED' })
  })

  it('快速重复批准或撤销同一申请时各只发送一次 mutation API', async () => {
    const slowDecision = deferred<AxiosResponse<{ code: number; message: string; data: undefined }>>()
    const slowRevoke = deferred<AxiosResponse<{ code: number; message: string; data: undefined }>>()
    vi.mocked(publicPoolApi.decideRequest).mockReturnValueOnce(slowDecision.promise)
    vi.mocked(publicPoolApi.revokeApproval).mockReturnValueOnce(slowRevoke.promise)
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as {
      decide: (requestId: number, decision: 'APPROVED' | 'REJECTED') => Promise<void>
      revoke: (requestId: number) => Promise<void>
    }

    const firstDecision = vm.decide(11, 'APPROVED')
    const duplicateDecision = vm.decide(11, 'APPROVED')
    expect(publicPoolApi.decideRequest).toHaveBeenCalledTimes(1)
    slowDecision.resolve(response({ code: 200, message: 'ok', data: undefined }))
    await Promise.all([firstDecision, duplicateDecision])

    const firstRevoke = vm.revoke(12)
    const duplicateRevoke = vm.revoke(12)
    expect(publicPoolApi.revokeApproval).toHaveBeenCalledTimes(1)
    slowRevoke.resolve(response({ code: 200, message: 'ok', data: undefined }))
    await Promise.all([firstRevoke, duplicateRevoke])
  })

  it('mutation API 成功后立即通知 changed，不等待慢刷新', async () => {
    const slowReload = deferred<AxiosResponse<{ code: number; message: string; data: PublicAccessRequestVO[] }>>()
    const wrapper = mountDialog()
    await settle()
    vi.mocked(publicPoolApi.listRequests).mockReturnValueOnce(slowReload.promise)
    const vm = wrapper.vm as unknown as {
      decide: (requestId: number, decision: 'APPROVED' | 'REJECTED') => Promise<void>
    }

    const pending = vm.decide(11, 'APPROVED')
    await flushPromises()
    expect(wrapper.emitted('changed')).toEqual([[]])

    slowReload.resolve(listResponse([]))
    await pending
  })

  it('审批进行中拦截关闭入口并禁用完成按钮', async () => {
    const slowDecision = deferred<AxiosResponse<{ code: number; message: string; data: undefined }>>()
    vi.mocked(publicPoolApi.decideRequest).mockReturnValueOnce(slowDecision.promise)
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as {
      decide: (requestId: number, decision: 'APPROVED' | 'REJECTED') => Promise<void>
    }
    const pending = vm.decide(11, 'APPROVED')
    await wrapper.vm.$nextTick()
    const modal = wrapper.findComponent(NModal)
    const done = wrapper.findAllComponents(NButton).find((button) => button.text() === '完成')

    modal.vm.$emit('update:show', false)
    await done!.trigger('click')
    expect(wrapper.emitted('update:show')).toBeUndefined()
    expect(done!.props('disabled')).toBe(true)

    slowDecision.resolve(response({ code: 200, message: 'ok', data: undefined }))
    await pending
  })

  it('同项目被强制关闭重开后保留防重，旧成功静默刷新新会话最终状态', async () => {
    vi.mocked(publicPoolApi.listRequests).mockResolvedValueOnce(listResponse([request(11, 7, 'PENDING')]))
    const slowDecision = deferred<AxiosResponse<{ code: number; message: string; data: undefined }>>()
    vi.mocked(publicPoolApi.decideRequest).mockReturnValueOnce(slowDecision.promise)
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as {
      decide: (requestId: number, decision: 'APPROVED' | 'REJECTED') => Promise<void>
    }
    const oldDecision = vm.decide(11, 'APPROVED')

    await wrapper.setProps({ show: false })
    vi.mocked(publicPoolApi.listRequests).mockResolvedValueOnce(listResponse([request(11, 7, 'PENDING')]))
    await wrapper.setProps({ show: true })
    await settle()
    const approve = wrapper.findAllComponents(NButton).find((button) => button.text() === '批准')
    expect(approve?.props('disabled')).toBe(true)
    expect(approve?.props('loading')).toBe(true)

    vi.mocked(publicPoolApi.listRequests).mockClear()
    await vm.decide(11, 'APPROVED')
    expect(publicPoolApi.decideRequest).toHaveBeenCalledTimes(1)

    vi.mocked(publicPoolApi.listRequests).mockResolvedValueOnce(listResponse([request(11, 7, 'APPROVED')]))
    slowDecision.resolve(response({ code: 200, message: 'ok', data: undefined }))
    await oldDecision
    expect(wrapper.emitted('changed')).toBeUndefined()
    expect(messageMock.success).not.toHaveBeenCalled()
    expect(publicPoolApi.listRequests).toHaveBeenCalledOnce()
    expect(publicPoolApi.listRequests).toHaveBeenCalledWith(7)
    const rows = (wrapper.vm as unknown as { rows: Array<{ statusLabel: string; actions: string[] }> }).rows
    expect(rows).toEqual([expect.objectContaining({ statusLabel: '已批准', actions: ['revoke'] })])
  })

  it('旧批准成功但静默 reload 失败时，本地对账为已批准且不污染新会话消息', async () => {
    vi.mocked(publicPoolApi.listRequests).mockResolvedValueOnce(listResponse([request(11, 7, 'PENDING')]))
    const slowDecision = deferred<AxiosResponse<{ code: number; message: string; data: undefined }>>()
    vi.mocked(publicPoolApi.decideRequest).mockReturnValueOnce(slowDecision.promise)
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as {
      decide: (requestId: number, decision: 'APPROVED' | 'REJECTED') => Promise<void>
      rows: Array<{ statusLabel: string; actions: string[] }>
      error: string
    }
    const oldDecision = vm.decide(11, 'APPROVED')

    await wrapper.setProps({ show: false })
    vi.mocked(publicPoolApi.listRequests).mockResolvedValueOnce(listResponse([request(11, 7, 'PENDING')]))
    await wrapper.setProps({ show: true })
    await settle()
    vi.mocked(publicPoolApi.listRequests).mockRejectedValueOnce(new Error('silent reload failed'))

    slowDecision.resolve(response({ code: 200, message: 'ok', data: undefined }))
    await oldDecision

    expect(vm.rows).toEqual([expect.objectContaining({ statusLabel: '已批准', actions: ['revoke'] })])
    expect(vm.error).toBe('')
    expect(wrapper.emitted('changed')).toBeUndefined()
    expect(messageMock.success).not.toHaveBeenCalled()
    expect(messageMock.error).not.toHaveBeenCalled()
  })

  it('静默 reload 被更新刷新淘汰时，不得用旧 mutation 状态覆盖权威结果', async () => {
    vi.mocked(publicPoolApi.listRequests).mockResolvedValueOnce(listResponse([request(11, 7, 'PENDING')]))
    const slowDecision = deferred<AxiosResponse<{ code: number; message: string; data: undefined }>>()
    const staleSilentReload = deferred<AxiosResponse<{ code: number; message: string; data: PublicAccessRequestVO[] }>>()
    vi.mocked(publicPoolApi.decideRequest).mockReturnValueOnce(slowDecision.promise)
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as {
      decide: (requestId: number, decision: 'APPROVED' | 'REJECTED') => Promise<void>
      reload: () => Promise<unknown>
      rows: Array<{ statusLabel: string; actions: string[] }>
    }
    const oldDecision = vm.decide(11, 'APPROVED')

    await wrapper.setProps({ show: false })
    vi.mocked(publicPoolApi.listRequests).mockResolvedValueOnce(listResponse([request(11, 7, 'PENDING')]))
    await wrapper.setProps({ show: true })
    await settle()

    vi.mocked(publicPoolApi.listRequests).mockReturnValueOnce(staleSilentReload.promise)
    slowDecision.resolve(response({ code: 200, message: 'ok', data: undefined }))
    await flushPromises()

    vi.mocked(publicPoolApi.listRequests).mockResolvedValueOnce(listResponse([request(11, 7, 'REVOKED')]))
    await vm.reload()
    staleSilentReload.reject(new Error('superseded request failed'))
    await oldDecision

    expect(vm.rows).toEqual([expect.objectContaining({ statusLabel: '已撤销', actions: [] })])
    expect(wrapper.emitted('changed')).toBeUndefined()
    expect(messageMock.error).not.toHaveBeenCalled()
  })

  it('加载失败显示明确错误，不伪装成空列表', async () => {
    vi.mocked(publicPoolApi.listRequests).mockRejectedValueOnce(new Error('offline'))
    const wrapper = mountDialog()
    await settle()

    expect(wrapper.text()).toContain('申请列表加载失败，请稍后重试')
    expect(wrapper.text()).not.toContain('暂无访问申请')
    expect(wrapper.text()).not.toContain('暂无数据')
    expect(messageMock.error).toHaveBeenCalled()
  })

  it('成功空列表只显示一个明确空态', async () => {
    const wrapper = mountDialog()
    await settle()

    expect(wrapper.text().match(/暂无访问申请/g)).toHaveLength(1)
    expect(wrapper.text()).not.toContain('暂无数据')
  })

  it('慢项目 A 的列表结果不得覆盖切换后的项目 B', async () => {
    const slowA = deferred<AxiosResponse<{ code: number; message: string; data: PublicAccessRequestVO[] }>>()
    vi.mocked(publicPoolApi.listRequests).mockReturnValueOnce(slowA.promise)
    const wrapper = mountDialog(7, '项目 A')

    vi.mocked(publicPoolApi.listRequests).mockResolvedValueOnce(listResponse([request(2, 8, 'PENDING', 88)]))
    await wrapper.setProps({ projectId: 8, projectName: '项目 B' })
    await settle()
    slowA.resolve(listResponse([request(1, 7, 'PENDING', 77)]))
    await settle()

    const vm = wrapper.vm as unknown as { rows: Array<{ applicant: string }> }
    expect(vm.rows).toEqual([expect.objectContaining({ applicant: '用户 #88' })])
  })

  it('旧项目决定完成后不得刷新或通知新项目', async () => {
    const wrapper = mountDialog(7, '项目 A')
    await settle()
    const slowDecision = deferred<AxiosResponse<{ code: number; message: string; data: undefined }>>()
    vi.mocked(publicPoolApi.decideRequest).mockReturnValueOnce(slowDecision.promise)
    const vm = wrapper.vm as unknown as {
      decide: (requestId: number, decision: 'APPROVED' | 'REJECTED') => Promise<void>
      rows: Array<{ applicant: string }>
    }
    vi.mocked(publicPoolApi.listRequests).mockClear()
    const oldDecision = vm.decide(11, 'APPROVED')

    vi.mocked(publicPoolApi.listRequests).mockResolvedValueOnce(listResponse([request(2, 8, 'PENDING', 88)]))
    await wrapper.setProps({ projectId: 8, projectName: '项目 B' })
    await settle()
    expect(publicPoolApi.listRequests).toHaveBeenCalledTimes(1)

    slowDecision.resolve(response({ code: 200, message: 'ok', data: undefined }))
    await oldDecision
    await settle()

    expect(publicPoolApi.listRequests).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('changed')).toBeUndefined()
    expect(vm.rows).toEqual([expect.objectContaining({ applicant: '用户 #88' })])
  })

  it('关闭或切换项目会立即清空旧列表与错误', async () => {
    vi.mocked(publicPoolApi.listRequests).mockResolvedValueOnce(listResponse([request(1, 7, 'PENDING')]))
    const wrapper = mountDialog()
    await settle()
    const vm = wrapper.vm as unknown as { rows: unknown[] }
    expect(vm.rows).toHaveLength(1)

    await wrapper.setProps({ show: false })
    expect(vm.rows).toEqual([])
    expect(wrapper.text()).not.toContain('申请列表加载失败')
  })

  it('同项目较旧刷新晚返回时不得覆盖最新结果', async () => {
    const wrapper = mountDialog()
    await settle()
    const oldReload = deferred<AxiosResponse<{ code: number; message: string; data: PublicAccessRequestVO[] }>>()
    const latestReload = deferred<AxiosResponse<{ code: number; message: string; data: PublicAccessRequestVO[] }>>()
    vi.mocked(publicPoolApi.listRequests)
      .mockReturnValueOnce(oldReload.promise)
      .mockReturnValueOnce(latestReload.promise)
    const vm = wrapper.vm as unknown as { reload: () => Promise<void>; rows: Array<{ applicant: string }> }

    const oldPending = vm.reload()
    const latestPending = vm.reload()
    latestReload.resolve(listResponse([request(2, 7, 'PENDING', 222)]))
    await latestPending
    oldReload.resolve(listResponse([request(1, 7, 'PENDING', 111)]))
    await oldPending

    expect(vm.rows).toEqual([expect.objectContaining({ applicant: '用户 #222' })])
  })

  it('同项目旧刷新先完成时，加载态保持到最新刷新完成', async () => {
    const wrapper = mountDialog()
    await settle()
    const oldReload = deferred<AxiosResponse<{ code: number; message: string; data: PublicAccessRequestVO[] }>>()
    const latestReload = deferred<AxiosResponse<{ code: number; message: string; data: PublicAccessRequestVO[] }>>()
    vi.mocked(publicPoolApi.listRequests)
      .mockReturnValueOnce(oldReload.promise)
      .mockReturnValueOnce(latestReload.promise)
    const vm = wrapper.vm as unknown as { reload: () => Promise<void>; loading: boolean }

    const oldPending = vm.reload()
    const latestPending = vm.reload()
    oldReload.resolve(listResponse([request(1, 7, 'PENDING')]))
    await oldPending
    expect(vm.loading).toBe(true)

    latestReload.resolve(listResponse([request(2, 7, 'PENDING')]))
    await latestPending
    expect(vm.loading).toBe(false)
  })
})
