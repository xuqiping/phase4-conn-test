import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { NButton, NModal, NRadio } from 'naive-ui'
import type { AxiosResponse } from 'axios'
import PublicPublishDialog from './PublicPublishDialog.vue'
import { publicPoolApi } from '@/api/assets'
import type { AssetProjectVO } from '@/types/asset'

const messageMock = { success: vi.fn(), error: vi.fn() }

vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/assets', () => ({
  publicPoolApi: { publish: vi.fn() }
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

function project(id = 7): AssetProjectVO {
  return {
    id,
    name: '镜头语言素材库',
    ownerId: 1,
    narrativeRoles: [],
    mediaTypes: [],
    role: 'OWNER',
    createdAt: '2026-08-10T08:00:00Z'
  }
}

function mountDialog(props: Partial<{ show: boolean; project: AssetProjectVO | null; isAdmin: boolean }> = {}) {
  return mount(PublicPublishDialog, {
    props: { show: true, project: project(), isAdmin: false, ...props },
    global: { stubs: { teleport: true } }
  })
}

async function settle() {
  await flushPromises()
}

describe('PublicPublishDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(publicPoolApi.publish).mockResolvedValue(response({ code: 200, message: 'ok', data: undefined }))
  })

  it('普通所有者默认开放使用，可选择审批后以正确模式发布', async () => {
    const wrapper = mountDialog()
    const vm = wrapper.vm as unknown as {
      mode: 'OPEN' | 'APPROVAL_REQUIRED'
      submit: () => Promise<void>
    }

    expect(vm.mode).toBe('OPEN')
    expect(wrapper.findAllComponents(NRadio)).toHaveLength(2)
    expect(wrapper.text()).toContain('所有人可直接只读使用')
    expect(wrapper.text()).toContain('所有人可查看摘要，使用前需要申请')

    vm.mode = 'APPROVAL_REQUIRED'
    await vm.submit()

    expect(publicPoolApi.publish).toHaveBeenCalledWith(7, { accessMode: 'APPROVAL_REQUIRED' })
  })

  it('管理员显示官方发布说明并固定为 OPEN，不提供审批模式选项', async () => {
    const wrapper = mountDialog({ isAdmin: true })
    const vm = wrapper.vm as unknown as { mode: string; submit: () => Promise<void> }

    expect(wrapper.text()).toContain('官方发布')
    expect(wrapper.text()).toContain('开放使用')
    expect(wrapper.findAllComponents(NRadio)).toHaveLength(0)
    expect(wrapper.text()).not.toContain('使用前需要申请')

    await vm.submit()
    expect(publicPoolApi.publish).toHaveBeenCalledWith(7, { accessMode: 'OPEN' })
  })

  it('成功后提示、通知项目 id 并关闭弹窗', async () => {
    const wrapper = mountDialog()
    const submit = wrapper.findAllComponents(NButton).find((button) => button.text() === '发布到公众池')

    expect(submit).toBeDefined()
    await submit!.trigger('click')
    await settle()

    expect(messageMock.success).toHaveBeenCalled()
    expect(wrapper.emitted('published')).toEqual([[7]])
    expect(wrapper.emitted('update:show')).toContainEqual([false])
  })

  it('失败时保留弹窗并显示独立错误，重新打开会重置状态', async () => {
    vi.mocked(publicPoolApi.publish).mockRejectedValueOnce(new Error('offline'))
    const wrapper = mountDialog()
    const vm = wrapper.vm as unknown as {
      mode: 'OPEN' | 'APPROVAL_REQUIRED'
      submit: () => Promise<void>
    }
    vm.mode = 'APPROVAL_REQUIRED'

    await vm.submit()
    await settle()

    expect(wrapper.text()).toContain('发布失败，请稍后重试')
    expect(wrapper.emitted('update:show')).toBeUndefined()
    expect(messageMock.error).toHaveBeenCalled()

    await wrapper.setProps({ show: false })
    await wrapper.setProps({ show: true })
    expect(vm.mode).toBe('OPEN')
    expect(wrapper.text()).not.toContain('发布失败，请稍后重试')
  })

  it('没有项目时不提交', async () => {
    const wrapper = mountDialog({ project: null })
    const vm = wrapper.vm as unknown as { submit: () => Promise<void> }

    await vm.submit()
    expect(publicPoolApi.publish).not.toHaveBeenCalled()
  })

  it('项目 A 的慢发布完成后不得关闭或污染项目 B', async () => {
    const slowA = deferred<AxiosResponse<{ code: number; message: string; data: undefined }>>()
    vi.mocked(publicPoolApi.publish).mockReturnValueOnce(slowA.promise)
    const wrapper = mountDialog({ project: project(7) })
    const vm = wrapper.vm as unknown as { submit: () => Promise<void>; submitting: boolean; error: string }
    const pendingA = vm.submit()
    expect(vm.submitting).toBe(true)

    await wrapper.setProps({ project: project(8) })
    expect(vm.submitting).toBe(false)
    slowA.resolve(response({ code: 200, message: 'ok', data: undefined }))
    await pendingA

    expect(wrapper.emitted('published')).toEqual([[7]])
    expect(wrapper.emitted('update:show')).toBeUndefined()
    expect(messageMock.success).not.toHaveBeenCalled()
    expect(vm.error).toBe('')
  })

  it('发布中同项目被强制关闭重开后不得重复 POST，旧成功只通知 published 不关闭新会话', async () => {
    const slowA = deferred<AxiosResponse<{ code: number; message: string; data: undefined }>>()
    vi.mocked(publicPoolApi.publish).mockReturnValueOnce(slowA.promise)
    const wrapper = mountDialog()
    const vm = wrapper.vm as unknown as {
      submit: () => Promise<void>
      submitting: boolean
      publishCompleted: boolean
      error: string
    }
    const pendingA = vm.submit()
    await wrapper.vm.$nextTick()
    const modal = wrapper.findComponent(NModal)
    const cancel = wrapper.findAllComponents(NButton).find((button) => button.text() === '取消')

    modal.vm.$emit('update:show', false)
    await cancel!.trigger('click')
    expect(wrapper.emitted('update:show')).toBeUndefined()
    expect(cancel!.props('disabled')).toBe(true)

    await wrapper.setProps({ show: false })
    await wrapper.setProps({ show: true })
    expect(vm.submitting).toBe(true)
    await vm.submit()
    expect(publicPoolApi.publish).toHaveBeenCalledTimes(1)

    slowA.resolve(response({ code: 200, message: 'ok', data: undefined }))
    await pendingA
    expect(vm.error).toBe('')
    expect(wrapper.emitted('published')).toEqual([[7]])
    expect(wrapper.emitted('update:show')).toBeUndefined()
    expect(messageMock.success).not.toHaveBeenCalled()
    expect(vm.publishCompleted).toBe(true)
    expect(wrapper.text()).toContain('已发布，等待列表刷新')

    await vm.submit()
    expect(publicPoolApi.publish).toHaveBeenCalledTimes(1)
  })

  it('发布成功后关闭重开同项目仍保持完成锁', async () => {
    const slowA = deferred<AxiosResponse<{ code: number; message: string; data: undefined }>>()
    vi.mocked(publicPoolApi.publish).mockReturnValueOnce(slowA.promise)
    const wrapper = mountDialog()
    const vm = wrapper.vm as unknown as { submit: () => Promise<void>; publishCompleted: boolean }
    const pendingA = vm.submit()

    await wrapper.setProps({ show: false })
    await wrapper.setProps({ show: true })
    slowA.resolve(response({ code: 200, message: 'ok', data: undefined }))
    await pendingA
    expect(vm.publishCompleted).toBe(true)

    await wrapper.setProps({ show: false })
    await wrapper.setProps({ show: true })
    expect(vm.publishCompleted).toBe(true)
    await vm.submit()
    expect(publicPoolApi.publish).toHaveBeenCalledTimes(1)
  })

  it('发布成功后切到 B 再回到滞后状态的 A 仍保持完成锁', async () => {
    const wrapper = mountDialog({ project: project(7) })
    const vm = wrapper.vm as unknown as { submit: () => Promise<void>; publishCompleted: boolean }

    await vm.submit()
    expect(vm.publishCompleted).toBe(true)
    await wrapper.setProps({ project: project(8) })
    expect(vm.publishCompleted).toBe(false)
    await wrapper.setProps({ project: project(7) })
    expect(vm.publishCompleted).toBe(true)
    await vm.submit()
    expect(publicPoolApi.publish).toHaveBeenCalledTimes(1)
  })

  it('完成锁仅在权威 prop 先确认已发布、再确认移出公众池后解除', async () => {
    const wrapper = mountDialog({ project: project(7) })
    const vm = wrapper.vm as unknown as { submit: () => Promise<void>; publishCompleted: boolean }

    await vm.submit()
    expect(vm.publishCompleted).toBe(true)
    await wrapper.setProps({ project: { ...project(7), publicPool: true } })
    expect(vm.publishCompleted).toBe(true)
    await vm.submit()
    expect(publicPoolApi.publish).toHaveBeenCalledTimes(1)

    await wrapper.setProps({ project: { ...project(7), publicPool: false } })
    expect(vm.publishCompleted).toBe(false)
    await vm.submit()
    expect(publicPoolApi.publish).toHaveBeenCalledTimes(2)
  })

  it('已观察到 publicPool=true 后兼容对象缺字段仍锁定，显式 false 才解锁', async () => {
    const wrapper = mountDialog({ project: { ...project(7), publicPool: true } })
    const vm = wrapper.vm as unknown as { submit: () => Promise<void>; publishCompleted: boolean }

    expect(vm.publishCompleted).toBe(true)
    await wrapper.setProps({ project: project(7) })
    expect(vm.publishCompleted).toBe(true)
    await vm.submit()
    expect(publicPoolApi.publish).not.toHaveBeenCalled()

    await wrapper.setProps({ project: { ...project(7), publicPool: false } })
    expect(vm.publishCompleted).toBe(false)
    await vm.submit()
    expect(publicPoolApi.publish).toHaveBeenCalledOnce()
  })

  it('同项目被强制关闭重开后，旧发布失败不得写入新会话错误', async () => {
    const slowA = deferred<AxiosResponse<{ code: number; message: string; data: undefined }>>()
    vi.mocked(publicPoolApi.publish).mockReturnValueOnce(slowA.promise)
    const wrapper = mountDialog()
    const vm = wrapper.vm as unknown as { submit: () => Promise<void>; submitting: boolean; error: string }
    const pendingA = vm.submit()

    await wrapper.setProps({ show: false })
    await wrapper.setProps({ show: true })
    expect(vm.submitting).toBe(true)
    await vm.submit()
    expect(publicPoolApi.publish).toHaveBeenCalledTimes(1)

    slowA.reject(new Error('old session failed'))
    await pendingA
    expect(vm.error).toBe('')
    expect(messageMock.error).not.toHaveBeenCalled()
    expect(wrapper.emitted('update:show')).toBeUndefined()
  })
})
