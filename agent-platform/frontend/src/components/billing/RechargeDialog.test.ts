import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import RechargeDialog from './RechargeDialog.vue'
import type { PaymentOrderVO } from '@/api/billing'

// mock API 层（不触网）
vi.mock('@/api/billing', async importOriginal => {
  const orig = await importOriginal<typeof import('@/api/billing')>()
  return {
    ...orig,
    billingApi: {
      ...orig.billingApi,
      createPaymentOrder: vi.fn(),
      getPaymentOrder: vi.fn(),
      cancelPaymentOrder: vi.fn(),
      mockTrigger: vi.fn()
    }
  }
})
import { billingApi } from '@/api/billing'

function makeOrder(over: Partial<PaymentOrderVO> = {}): PaymentOrderVO {
  return {
    id: 42,
    createdAt: '2026-08-21T10:00:00Z',
    amountYuan: 100,
    pointsGranted: 1000,
    status: 'PENDING',
    channel: 'MOCK',
    payerAccount: null,
    expireAt: null,
    paidAt: null,
    payToken: 'tok',
    ...over
  }
}

function apiOk<T>(data: T) {
  return { data: { code: 200, msg: 'success', data } }
}

const stubs = {
  NModal: { template: '<div><slot /><slot name="footer" /></div>', props: ['show'] },
  NForm: { template: '<div><slot /></div>' },
  NFormItem: { template: '<div><slot /></div>' },
  NInputNumber: true,
  NSelect: true,
  NButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  NSpace: { template: '<div><slot /></div>' },
  NAlert: { template: '<div><slot /></div>' },
  NStatistic: true,
  NSpin: true
}

async function openDialog(props: Record<string, unknown> = {}) {
  const wrapper = mount(RechargeDialog, {
    props: { show: false, channels: ['MOCK'], ...props },
    global: { stubs }
  })
  await wrapper.setProps({ show: true })
  await nextTick()
  return wrapper
}

/** 直接把对话框推进到收银台阶段（绕过表单输入组件 stub）。 */
async function toCashier(wrapper: ReturnType<typeof mount>, idemCapture?: string[]) {
  vi.mocked(billingApi.createPaymentOrder).mockImplementation(async (req) => {
    idemCapture?.push(req.idemKey ?? '')
    return apiOk(makeOrder()) as never
  })
  const vm = wrapper.vm as unknown as {
    amountYuan: number; channel: string; submit: () => Promise<void>
  }
  vm.amountYuan = 100
  vm.channel = 'MOCK'
  await vm.submit()
  await nextTick()
}

describe('RechargeDialog（7x#3 充值全链）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('idemKey 每次打开对话框重新生成（会话内唯一，防双击双扣）', async () => {
    const captured: string[] = []
    const wrapper = await openDialog()
    await toCashier(wrapper, captured)

    // 关掉重开 → 新 idemKey
    await wrapper.setProps({ show: false })
    await wrapper.setProps({ show: true })
    await toCashier(wrapper, captured)

    expect(captured).toHaveLength(2)
    expect(captured[0]).toBeTruthy()
    expect(captured[1]).toBeTruthy()
    expect(captured[0]).not.toBe(captured[1])
  })

  it('轮询 PAID → emit paid 且停止轮询', async () => {
    const wrapper = await openDialog()
    await toCashier(wrapper)
    vi.mocked(billingApi.getPaymentOrder).mockResolvedValue(apiOk(makeOrder({ status: 'PAID' })) as never)

    await vi.advanceTimersByTimeAsync(2000)

    expect(wrapper.emitted('paid')).toBeTruthy()
    expect(wrapper.emitted('paid')![0][0]).toMatchObject({ status: 'PAID' })
    // 停止后不再查单
    const calls = vi.mocked(billingApi.getPaymentOrder).mock.calls.length
    await vi.advanceTimersByTimeAsync(6000)
    expect(vi.mocked(billingApi.getPaymentOrder).mock.calls.length).toBe(calls)
  })

  it('轮询 FAILED/CLOSED → emit settled 且停止', async () => {
    const wrapper = await openDialog()
    await toCashier(wrapper)
    vi.mocked(billingApi.getPaymentOrder).mockResolvedValue(apiOk(makeOrder({ status: 'CLOSED' })) as never)

    await vi.advanceTimersByTimeAsync(2000)

    expect(wrapper.emitted('settled')).toBeTruthy()
    expect(wrapper.emitted('paid')).toBeFalsy()
  })

  it('轮询超 30 次仍 PENDING → 超时 emit settled 停止', async () => {
    const wrapper = await openDialog()
    await toCashier(wrapper)
    vi.mocked(billingApi.getPaymentOrder).mockResolvedValue(apiOk(makeOrder()) as never)

    // 30 次 × 2s = 60s
    await vi.advanceTimersByTimeAsync(2000 * 31)

    expect(wrapper.emitted('settled')).toBeTruthy()
    expect(wrapper.emitted('paid')).toBeFalsy()
    const calls = vi.mocked(billingApi.getPaymentOrder).mock.calls.length
    expect(calls).toBeLessThanOrEqual(31)
    await vi.advanceTimersByTimeAsync(10000)
    expect(vi.mocked(billingApi.getPaymentOrder).mock.calls.length).toBe(calls)
  })

  it('mock 收银台：模拟成功触发 mockTrigger(success=true)；模拟失败直接 settled', async () => {
    const wrapper = await openDialog()
    await toCashier(wrapper)
    vi.mocked(billingApi.getPaymentOrder).mockResolvedValue(apiOk(makeOrder()) as never)
    vi.mocked(billingApi.mockTrigger).mockResolvedValue(apiOk({ orderId: 42, accepted: true }) as never)

    const vm = wrapper.vm as unknown as { mockPay: (s: boolean) => Promise<void> }
    await vm.mockPay(true)
    expect(billingApi.mockTrigger).toHaveBeenCalledWith({ orderId: 42, success: true })

    await vm.mockPay(false)
    expect(billingApi.mockTrigger).toHaveBeenCalledWith({ orderId: 42, success: false })
    expect(wrapper.emitted('settled')).toBeTruthy()
  })

  it('关闭对话框停止轮询', async () => {
    const wrapper = await openDialog()
    await toCashier(wrapper)
    vi.mocked(billingApi.getPaymentOrder).mockResolvedValue(apiOk(makeOrder()) as never)

    await vi.advanceTimersByTimeAsync(2000)
    const calls = vi.mocked(billingApi.getPaymentOrder).mock.calls.length
    await wrapper.setProps({ show: false })
    await vi.advanceTimersByTimeAsync(10000)
    expect(vi.mocked(billingApi.getPaymentOrder).mock.calls.length).toBe(calls)
  })
})
