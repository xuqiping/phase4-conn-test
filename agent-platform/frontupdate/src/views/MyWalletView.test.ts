import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MyWalletView from './MyWalletView.vue'
import { billingApi } from '@/api/billing'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/billing', async (importOriginal) => {
  const orig = await importOriginal<typeof import('@/api/billing')>()
  return {
    ...orig,
    billingApi: {
      myWallet: vi.fn(),
      myUsage: vi.fn(),
      paymentChannels: vi.fn(),
      myRecharges: vi.fn()
    }
  }
})

vi.mock('@/api/projectGroup', () => ({
  projectGroupApi: { mine: vi.fn() }
}))

import { projectGroupApi } from '@/api/projectGroup'

function apiOk<T>(data: T) {
  return { data: { code: 200, msg: 'success', data } }
}

const stubs = {
  NCard: { template: '<div><slot name="header-extra" /><slot /></div>' },
  NStatistic: true,
  NTag: { template: '<span><slot /></span>' },
  NSpace: { template: '<div><slot /></div>' },
  NButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  NSelect: true,
  // 表格渲染行数据为文本，供字段断言
  NDataTable: {
    props: ['data', 'columns'],
    template: '<div class="table"><div v-for="(r, i) in data" :key="i" class="row">' +
      '<span v-for="c in columns" :key="c.key">{{ c.render ? c.render(r) : r[c.key] }}</span></div></div>'
  },
  RechargeDialog: true
}

describe('MyWalletView 充值记录（7x#1 六字段渲染）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(billingApi.myWallet).mockResolvedValue(apiOk({ balance: 500, recentLedger: [] }) as never)
    vi.mocked(billingApi.myUsage).mockResolvedValue(apiOk([]) as never)
    vi.mocked(projectGroupApi.mine).mockResolvedValue(apiOk([]) as never)
  })

  it('六字段行渲染：渠道中文/金额/积分/充值后余额/状态中文', async () => {
    vi.mocked(billingApi.paymentChannels).mockResolvedValue(apiOk(['MOCK']) as never)
    vi.mocked(billingApi.myRecharges).mockResolvedValue(apiOk({
      page: {
        records: [{
          id: 1, createdAt: '2026-08-21T10:00:00Z', channel: 'MOCK',
          payerAccount: '138****1234', amountYuan: 100, pointsGranted: 1000,
          balanceAfter: 1500, status: 'PAID'
        }],
        total: 1, pageNum: 1, pageSize: 10
      },
      totalPaidAmount: 100,
      totalPaidPoints: 1000
    }) as never)

    const wrapper = mount(MyWalletView, { global: { stubs } })
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('模拟支付')       // 渠道中文映射
    expect(text).toContain('138****1234')    // 付款账号
    expect(text).toContain('100.00')         // 金额
    expect(text).toContain('1000.00')        // 积分
    expect(text).toContain('1500.00')        // 充值后余额
    expect(text).toContain('已支付')          // 状态中文
    // 累计条
    expect(text).toContain('累计充值')
  })

  it('无可用渠道 → 隐藏充值按钮；有渠道 → 显示', async () => {
    vi.mocked(billingApi.myRecharges).mockResolvedValue(apiOk({
      page: { records: [], total: 0, pageNum: 1, pageSize: 10 },
      totalPaidAmount: 0, totalPaidPoints: 0
    }) as never)
    vi.mocked(billingApi.paymentChannels).mockResolvedValue(apiOk([]) as never)

    const wrapper = mount(MyWalletView, { global: { stubs } })
    await flushPromises()
    expect(wrapper.findAll('button').filter(b => b.text() === '充值')).toHaveLength(0)

    vi.mocked(billingApi.paymentChannels).mockResolvedValue(apiOk(['MOCK']) as never)
    const wrapper2 = mount(MyWalletView, { global: { stubs } })
    await flushPromises()
    expect(wrapper2.findAll('button').filter(b => b.text() === '充值')).toHaveLength(1)
  })

  it('未入账状态（PENDING）balanceAfter=null 显「—」', async () => {
    vi.mocked(billingApi.paymentChannels).mockResolvedValue(apiOk(['MOCK']) as never)
    vi.mocked(billingApi.myRecharges).mockResolvedValue(apiOk({
      page: {
        records: [{
          id: 2, createdAt: '2026-08-21T11:00:00Z', channel: 'MOCK',
          payerAccount: null, amountYuan: 50, pointsGranted: 500,
          balanceAfter: null, status: 'PENDING'
        }],
        total: 1, pageNum: 1, pageSize: 10
      },
      totalPaidAmount: 0,
      totalPaidPoints: 0
    }) as never)

    const wrapper = mount(MyWalletView, { global: { stubs } })
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('待支付')
    expect(text).toContain('—')
  })
})
