import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PaymentChannelConfigView from './PaymentChannelConfigView.vue'
import { billingApi, type PaymentChannelConfigVO } from '@/api/billing'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const dialogMock = { warning: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock, useDialog: () => dialogMock }
})

vi.mock('@/api/billing', async (importOriginal) => {
  const orig = await importOriginal<typeof import('@/api/billing')>()
  return {
    ...orig,
    billingApi: {
      ...orig.billingApi,
      adminPaymentChannels: vi.fn(),
      savePaymentChannelConfig: vi.fn()
    }
  }
})

function apiOk<T>(data: T) {
  return { data: { code: 200, msg: 'success', data } }
}

const chRow = (over: Partial<PaymentChannelConfigVO> = {}): PaymentChannelConfigVO => ({
  channel: 'ALIPAY',
  configured: true,
  tails: { appId: '****6789', privateKey: '****AASC', alipayPublicKey: '****AQ8A' },
  updatedAt: '2026-08-21T10:00:00Z',
  updatedBy: 1,
  ...over
})

type Vm = {
  channels: PaymentChannelConfigVO[]
  forms: Record<string, Record<string, string>>
  hasAnyInput: (c: string) => boolean
  buildPayload: (c: string) => Record<string, string>
  confirmSave: (c: string) => void
  save: (c: string, p: Record<string, string>) => Promise<void>
  load: () => Promise<void>
}

function mountView() {
  const wrapper = mount(PaymentChannelConfigView)
  return { wrapper, vm: wrapper.vm as unknown as Vm }
}

describe('PaymentChannelConfigView（7x 支付渠道网页配置）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(billingApi.savePaymentChannelConfig).mockResolvedValue(apiOk(null) as never)
  })

  it('加载两渠道：已配置显尾巴脱敏、未配置显「未配置」', async () => {
    vi.mocked(billingApi.adminPaymentChannels).mockResolvedValue(apiOk([
      chRow(),
      chRow({ channel: 'WECHAT', configured: false, tails: {}, updatedAt: null, updatedBy: null })
    ]) as never)

    const { wrapper } = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('支付宝')
    expect(text).toContain('微信')
    expect(text).toContain('已配置')
    expect(text).toContain('未配置')
    // placeholder 脱敏回显
    const input = wrapper.find('input')
    expect(input.attributes('placeholder')).toContain('****6789')
    // 页面任何位置不出现完整明文形态（只有尾巴）
    expect(text).not.toContain('2021000123456789')
  })

  it('buildPayload 只收非空字段（留空=保持原值，不进 payload）', async () => {
    vi.mocked(billingApi.adminPaymentChannels).mockResolvedValue(apiOk([chRow()]) as never)
    const { vm } = mountView()
    await flushPromises()

    vm.forms.ALIPAY = { appId: '  ', privateKey: '  new-key-9w8x  ', alipayPublicKey: '' }
    expect(vm.hasAnyInput('ALIPAY')).toBe(true)
    expect(vm.buildPayload('ALIPAY')).toEqual({ privateKey: 'new-key-9w8x' })

    vm.forms.WECHAT = { mchId: '', appId: '', apiV3Key: '' }
    expect(vm.hasAnyInput('WECHAT')).toBe(false)
  })

  it('保存走二次确认，确认后调 PUT 并刷新清空表单', async () => {
    vi.mocked(billingApi.adminPaymentChannels).mockResolvedValue(apiOk([chRow()]) as never)
    const { vm } = mountView()
    await flushPromises()

    vm.forms.ALIPAY = { privateKey: 'new-key-9w8x' }
    vm.confirmSave('ALIPAY')

    expect(dialogMock.warning).toHaveBeenCalledTimes(1)
    expect(billingApi.savePaymentChannelConfig).not.toHaveBeenCalled()

    const opts = dialogMock.warning.mock.calls[0][0] as { onPositiveClick: () => void }
    opts.onPositiveClick()
    await flushPromises()

    expect(billingApi.savePaymentChannelConfig).toHaveBeenCalledWith('ALIPAY', { privateKey: 'new-key-9w8x' })
    expect(messageMock.success).toHaveBeenCalledWith('已保存（加密存储）')
    expect(vm.forms.ALIPAY).toEqual({})           // 清空防二次误提交
    expect(billingApi.adminPaymentChannels).toHaveBeenCalledTimes(2) // 保存后刷新
  })

  it('空表单不弹确认', async () => {
    vi.mocked(billingApi.adminPaymentChannels).mockResolvedValue(apiOk([chRow()]) as never)
    const { vm } = mountView()
    await flushPromises()

    vm.confirmSave('ALIPAY')
    expect(dialogMock.warning).not.toHaveBeenCalled()
  })
})
