import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import WebSearchSettingsTab from './WebSearchSettingsTab.vue'
import { systemApi, type WebSearchSettings } from '@/api/system'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/system', async (importOriginal) => {
  const orig = await importOriginal<typeof import('@/api/system')>()
  return {
    ...orig,
    systemApi: {
      ...orig.systemApi,
      getWebSearchSettings: vi.fn(),
      updateWebSearchSettings: vi.fn(),
      testWebSearch: vi.fn()
    }
  }
})

function apiOk<T>(data: T) {
  return { data: { code: 200, msg: 'success', data } }
}

const vo = (over: Partial<WebSearchSettings> = {}): WebSearchSettings => ({
  enabled: true,
  tavilyEnabled: true,
  activeProvider: 'tavily',
  maxResults: 5,
  timeoutMs: 10000,
  hasTavilyKey: true,
  builtinConfigured: true,
  providerAvailability: { tavily: true, builtin: true, serper: false },
  ...over
})

type Vm = {
  enabled: boolean
  tavilyEnabled: boolean
  activeProvider: string
  tavilyKey: string
  hasTavilyKey: boolean
  testResult: { results: number; activeProvider: string } | null
  handleSaveTavilyEnabled: (v: boolean) => void
  handleSaveKeys: () => Promise<void>
  handleTest: () => Promise<void>
}

function mountTab() {
  const wrapper = mount(WebSearchSettingsTab)
  return { wrapper, vm: wrapper.vm as unknown as Vm }
}

describe('WebSearchSettingsTab（修复IX+ Tavily 开关制路由）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('渲染：Tavily 开关 + 派生路由标签；无 provider 手选下拉、无 serper/bing 输入', async () => {
    vi.mocked(systemApi.getWebSearchSettings).mockResolvedValue(apiOk(vo()) as never)
    const { wrapper, vm } = mountTab()
    await flushPromises()

    expect(vm.tavilyEnabled).toBe(true)
    // Tavily 开关 hint + 派生路由标签（form-item label 不进 text()，断 hint/标签）
    expect(wrapper.text()).toContain('智能对话与无限画布 chat 同规则')
    expect(wrapper.text()).toContain('Tavily（外部）')
    // provider 手选下拉已删；serper/bing key 输入已删
    expect(wrapper.text()).not.toContain('默认 Provider')
    expect(wrapper.text()).not.toContain('Serper Key')
    expect(wrapper.text()).not.toContain('Bing Key')
  })

  it('路由派生回显：tavilyEnabled=false → 显示自建 SearXNG', async () => {
    vi.mocked(systemApi.getWebSearchSettings).mockResolvedValue(
      apiOk(vo({ tavilyEnabled: false, activeProvider: 'builtin' })) as never)
    const { wrapper, vm } = mountTab()
    await flushPromises()

    expect(vm.activeProvider).toBe('builtin')
    expect(wrapper.text()).toContain('自建 SearXNG（builtin）')
  })

  it('拨 Tavily 开关 → PUT { tavilyEnabled }，并用回显 VO 刷新', async () => {
    vi.mocked(systemApi.getWebSearchSettings).mockResolvedValue(apiOk(vo()) as never)
    vi.mocked(systemApi.updateWebSearchSettings).mockResolvedValue(
      apiOk(vo({ tavilyEnabled: false, activeProvider: 'builtin' })) as never)
    const { vm } = mountTab()
    await flushPromises()

    vm.handleSaveTavilyEnabled(false)
    await flushPromises()

    expect(systemApi.updateWebSearchSettings).toHaveBeenCalledWith({ tavilyEnabled: false })
    expect(vm.activeProvider).toBe('builtin')
    expect(messageMock.success).toHaveBeenCalled()
  })

  it('保存 key：只收 tavilyKey 非空值；留空不改', async () => {
    vi.mocked(systemApi.getWebSearchSettings).mockResolvedValue(apiOk(vo()) as never)
    vi.mocked(systemApi.updateWebSearchSettings).mockResolvedValue(apiOk(vo()) as never)
    const { vm } = mountTab()
    await flushPromises()

    // 留空 → 不发请求
    await vm.handleSaveKeys()
    expect(systemApi.updateWebSearchSettings).not.toHaveBeenCalled()
    expect(messageMock.info).toHaveBeenCalled()

    vm.tavilyKey = 'tvly-new-key'
    await vm.handleSaveKeys()
    expect(systemApi.updateWebSearchSettings).toHaveBeenCalledWith({ tavilyKey: 'tvly-new-key' })
  })

  it('测试连通：调 test 接口并回显派生路由', async () => {
    vi.mocked(systemApi.getWebSearchSettings).mockResolvedValue(apiOk(vo()) as never)
    vi.mocked(systemApi.testWebSearch).mockResolvedValue(
      apiOk({ results: 3, providerAvailability: { tavily: true, builtin: true }, activeProvider: 'tavily', enabled: true }) as never)
    const { wrapper, vm } = mountTab()
    await flushPromises()

    await vm.handleTest()
    await flushPromises()

    expect(systemApi.testWebSearch).toHaveBeenCalled()
    expect(vm.testResult?.results).toBe(3)
    expect(wrapper.text()).toContain('当前路由 = Tavily')
  })

  it('可用性标签只显示 tavily/builtin（serper 即使回传也不显示）', async () => {
    vi.mocked(systemApi.getWebSearchSettings).mockResolvedValue(apiOk(vo()) as never)
    const { wrapper } = mountTab()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('tavily：可用')
    expect(text).toContain('builtin：可用')
    expect(text).not.toContain('serper：')
  })
})
