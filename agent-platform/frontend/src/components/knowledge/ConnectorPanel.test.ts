import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ConnectorPanel from './ConnectorPanel.vue'
import { knowledgeApi } from '@/api/knowledge'
import type { AxiosResponse } from 'axios'
import type { KnowledgeConnector } from '@/api/knowledge'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const dialogMock = { warning: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock, useDialog: () => dialogMock }
})

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    listConnectors: vi.fn(),
    createConnector: vi.fn(),
    updateConnector: vi.fn(),
    deleteConnector: vi.fn(),
    enableConnector: vi.fn(),
    disableConnector: vi.fn(),
    syncConnectorNow: vi.fn()
  }
}))

function response<T>(data: T) {
  return {
    data: { code: 200, message: 'ok', data },
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: {} as never }
  } as never as AxiosResponse<T>
}

function mkConnector(id: number, over: Partial<KnowledgeConnector> = {}): KnowledgeConnector {
  return {
    id,
    kbId: 1,
    type: 'URL_SITE',
    name: `连接器${id}`,
    status: 'ENABLED',
    scheduleCron: '0 0 4 * * *',
    syncOnSourceDelete: false,
    lastSyncAt: '2026-09-01T04:00:00Z',
    lastSyncSummary: '新增2/更新1/复活0/重试0/隔离0/删除0/跳过0/错误0',
    syncErrorStreak: 0,
    createdAt: '2026-08-30T00:00:00Z',
    ...over
  } as KnowledgeConnector
}

async function mountPanel(connectors: KnowledgeConnector[] = [mkConnector(1)]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  vi.mocked(knowledgeApi.listConnectors).mockResolvedValue(response(connectors) as never)
  const wrapper = mount(ConnectorPanel, {
    props: { kbId: 1 },
    global: { plugins: [pinia] }
  })
  await flushPromises()
  return wrapper
}

describe('ConnectorPanel · WP6 Step4 连接器管理面板', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
  })

  it('挂载即拉列表：类型中文名/摘要/操作按钮渲染', async () => {
    const wrapper = await mountPanel([mkConnector(1), mkConnector(2, { type: 'S3', name: '网盘' })])
    expect(knowledgeApi.listConnectors).toHaveBeenCalledWith(1)
    const text = wrapper.text()
    expect(text).toContain('URL 站点')
    expect(text).toContain('S3 网盘')
    expect(text).toContain('新增2/更新1')
    expect(text).toContain('立即同步')
    expect(text).toContain('停用')
  })

  it('状态渲染：ERROR 红标+连续错误轮数，DISABLED 灰标+启用按钮', async () => {
    const wrapper = await mountPanel([
      mkConnector(1, { status: 'ERROR', syncErrorStreak: 3, lastSyncSummary: '错误：连接超时' }),
      mkConnector(2, { status: 'DISABLED' })
    ])
    const text = wrapper.text()
    expect(text).toContain('错误')
    expect(text).toContain('×3')
    expect(text).toContain('已停用')
    expect(text).toContain('启用')
  })

  it('新建表单分支：URL_SITE 显示种子地址；切 S3 换 endpoint/bucket/AK/SK 隐藏种子', async () => {
    const wrapper = await mountPanel()
    await wrapper.find('button').trigger('click')   // 第一个按钮=新建连接器（NModal 传送 body）
    await flushPromises()
    let body = document.body.textContent || ''
    expect(body).toContain('新建连接器')
    expect(body).toContain('种子地址')

    // 切到 S3：种子地址消失，S3 字段出现
    ;(wrapper.vm as unknown as { form: { type: string } }).form.type = 'S3'
    await flushPromises()
    body = document.body.textContent || ''
    expect(body).not.toContain('种子地址')
    expect(body).toContain('Bucket')
    expect(body).toContain('Access Key')
    expect(body).toContain('Secret Key')
  })

  it('禁存门：URL_SITE 未填种子地址创建按钮禁用（canSubmit=false）', async () => {
    const wrapper = await mountPanel()
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(document.body.textContent || '').toContain('创建')
    // canSubmit 未暴露到实例——用「创建」按钮 disabled 间接验：表单空 → 未调用过 createConnector
    const createBtn = [...document.body.querySelectorAll('button')].find(b => b.textContent?.includes('创建'))
    expect(createBtn).toBeTruthy()
    expect(createBtn!.hasAttribute('disabled')).toBe(true)
    expect(knowledgeApi.createConnector).not.toHaveBeenCalled()
  })

  it('提交新建：config+cron 预设透传，成功后刷新列表', async () => {
    const wrapper = await mountPanel()
    const vm = wrapper.vm as unknown as {
      form: { type: string; name: string; syncOnSourceDelete: boolean }
      config: Record<string, string>
      cronPreset: string
      submit: () => Promise<void>
    }
    vm.form.name = '团队 Wiki'
    vm.form.type = 'URL_SITE'
    vm.config.seedUrl = 'https://example.com/docs/'
    vm.cronPreset = '0 17 * * * *'
    vi.mocked(knowledgeApi.createConnector).mockResolvedValue(response(null) as never)
    vi.mocked(knowledgeApi.listConnectors).mockResolvedValue(response([mkConnector(1)]) as never)

    await vm.submit()
    await flushPromises()

    expect(knowledgeApi.createConnector).toHaveBeenCalledWith(1, {
      name: '团队 Wiki',
      type: 'URL_SITE',
      config: { seedUrl: 'https://example.com/docs/' },
      scheduleCron: '0 17 * * * *',
      syncOnSourceDelete: false
    })
    expect(messageMock.success).toHaveBeenCalled()
  })

  it('启停：DISABLED 行走 enableConnector，ENABLED 行走 disableConnector', async () => {
    const wrapper = await mountPanel([mkConnector(1, { status: 'DISABLED' }), mkConnector(2, { status: 'ENABLED' })])
    const vm = wrapper.vm as unknown as {
      toggle: (r: KnowledgeConnector, a: 'enable' | 'disable') => Promise<void>
    }
    vi.mocked(knowledgeApi.enableConnector).mockResolvedValue(response(null) as never)
    vi.mocked(knowledgeApi.disableConnector).mockResolvedValue(response(null) as never)
    vi.mocked(knowledgeApi.listConnectors).mockResolvedValue(response([]) as never)

    await vm.toggle(mkConnector(1, { status: 'DISABLED' }), 'enable')
    await vm.toggle(mkConnector(2, { status: 'ENABLED' }), 'disable')
    await flushPromises()

    expect(knowledgeApi.enableConnector).toHaveBeenCalledWith(1)
    expect(knowledgeApi.disableConnector).toHaveBeenCalledWith(2)
  })

  it('立即同步：202 触发后提示异步+延迟刷新', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountPanel([mkConnector(1)])
      const vm = wrapper.vm as unknown as { syncNow: (r: KnowledgeConnector) => Promise<void> }
      vi.mocked(knowledgeApi.syncConnectorNow).mockResolvedValue(response(null) as never)

      await vm.syncNow(mkConnector(1))
      expect(knowledgeApi.syncConnectorNow).toHaveBeenCalledWith(1)
      expect(messageMock.success).toHaveBeenCalled()

      // 5s 延迟刷新（202 异步拿结果）
      vi.mocked(knowledgeApi.listConnectors).mockClear()
      vi.advanceTimersByTime(5000)
      await flushPromises()
      expect(knowledgeApi.listConnectors).toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })
})
