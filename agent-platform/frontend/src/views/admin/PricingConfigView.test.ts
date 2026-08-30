import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { AxiosResponse } from 'axios'
import PricingConfigView from './PricingConfigView.vue'
import { billingApi } from '@/api/billing'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const dialogMock = { info: vi.fn(), warning: vi.fn(), success: vi.fn(), error: vi.fn(), create: vi.fn() }

vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  // mount 无 NDialogProvider，useDialog() 会抛 no provider —— 与 VideoGenView.test 同款 harness 修复
  return { ...actual, useMessage: () => messageMock, useDialog: () => dialogMock }
})

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasPermission: () => true })
}))

vi.mock('@/api/billing', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/billing')>()
  return {
    ...actual,
    billingApi: {
      listPricingRules: vi.fn(),
      availablePricingModels: vi.fn(),
      createPricingRule: vi.fn(),
      updatePricingRule: vi.fn(),
      listRatioTiers: vi.fn(),
      createRatioTier: vi.fn(),
      updateRatioTier: vi.fn(),
      deleteRatioTier: vi.fn(),
      // D5（V160）：视频预估偏差展示——load 里 Promise.all 三查，缺 mock 会 unhandled rejection
      videoEstDeviation: vi.fn()
    }
  }
})

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function mountView() {
  return mount(PricingConfigView, { global: { stubs: { teleport: true } } })
}

// 页面含 ModuleScene（useThemeStore），挂载前需活动 pinia
beforeEach(() => setActivePinia(createPinia()))

describe('PricingConfigView FR-F20-01', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(billingApi.listPricingRules).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [] })
    )
    vi.mocked(billingApi.listRatioTiers).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [] })
    )
    vi.mocked(billingApi.videoEstDeviation).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [] })
    )
    vi.mocked(billingApi.availablePricingModels).mockResolvedValue(
      response({
        code: 200,
        message: 'ok',
        data: [{ providerId: 7, providerName: '豆包', model: 'seed-chat', kind: 'CHAT' }]
      })
    )
  })

  it('打开新增时加载候选，选择后同步三元身份', async () => {
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      openPricingModal: () => Promise<void>
      selectedCandidateKey: string | null
      onCandidateChange: (value: string | null) => void
      pricingForm: { providerId?: number | null; model?: string | null; kind: string }
    }

    await vm.openPricingModal()
    await flushPromises()
    expect(billingApi.availablePricingModels).toHaveBeenCalledOnce()

    // D6（V160）：候选 key 3 段（provider/model/参考面）——后端已去分辨率档；7x-1 的 4 段口径已废弃
    vm.onCandidateChange('7\u0000seed-chat\u00000')
    expect(vm.pricingForm).toMatchObject({ providerId: 7, model: 'seed-chat', kind: 'CHAT' })
  })

  it('编辑时锁定 provider、model、kind 并说明原因', async () => {
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      openPricingModal: (rule: Record<string, unknown>) => Promise<void>
    }

    await vm.openPricingModal({
      id: 8,
      kind: 'CHAT',
      providerId: 7,
      model: 'seed-chat',
      priceInputPerMillion: 1,
      priceOutputPerMillion: 2,
      videoBillingMode: null,
      pricePerSecond: null,
      pricePerImage: null,
      effectiveFrom: '2026-08-10T00:00:00Z'
    })
    await flushPromises()

    expect(wrapper.text()).toContain('模型身份已锁定')
    expect(wrapper.findAll('.n-input--disabled')).toHaveLength(2)
    expect(wrapper.find('.n-base-selection--disabled').exists()).toBe(true)
    expect(billingApi.availablePricingModels).not.toHaveBeenCalled()
  })

  it('没有候选时显示明确空态', async () => {
    vi.mocked(billingApi.availablePricingModels).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [] })
    )
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as { openPricingModal: () => Promise<void> }

    await vm.openPricingModal()
    await flushPromises()

    expect(wrapper.text()).toContain('所有全局模型均已配置价表')
  })

  it('候选加载失败时保留弹窗并显示错误', async () => {
    vi.mocked(billingApi.availablePricingModels).mockRejectedValue(new Error('network'))
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as { openPricingModal: () => Promise<void> }

    await expect(vm.openPricingModal()).resolves.toBeUndefined()
    await flushPromises()

    expect(wrapper.text()).toContain('全局模型候选加载失败')
    expect(wrapper.text()).toContain('价表')
  })

  it('未选择候选时禁止创建', async () => {
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as { savePricing: () => Promise<void> }

    await vm.savePricing()

    expect(billingApi.createPricingRule).not.toHaveBeenCalled()
    expect(messageMock.error).toHaveBeenCalledWith('请先选择一个未配置的全局模型')
  })
})
