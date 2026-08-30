import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProviderManageTab from './ProviderManageTab.vue'

// 修复VIII B4（VIII-5）：导出 POST + 密码二次确认——
// 弹窗交互（打开/重置/空密码拦截/失败保留可重试/成功关窗下载）走 vm 直驱（同 12x 通道页模式）

const apiMock = vi.hoisted(() => ({
  listProviders: vi.fn(),
  exportProviders: vi.fn()
}))
const sysApiMock = vi.hoisted(() => ({
  getLlmModelDefaults: vi.fn(),
  updateLlmModelDefaults: vi.fn()
}))
const msgMock = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn(),
  warning: vi.fn(),
  info: vi.fn()
}))

vi.mock('@/api/llm', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/llm')>()
  return {
    ...original,
    llmApi: {
      ...original.llmApi,
      listProviders: apiMock.listProviders,
      exportProviders: apiMock.exportProviders
    }
  }
})
vi.mock('@/api/system', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/system')>()
  return {
    ...original,
    systemApi: {
      ...original.systemApi,
      getLlmModelDefaults: sysApiMock.getLlmModelDefaults,
      updateLlmModelDefaults: sysApiMock.updateLlmModelDefaults
    }
  }
})
vi.mock('naive-ui', async (importOriginal) => {
  const original = await importOriginal<typeof import('naive-ui')>()
  return {
    ...original,
    useMessage: () => msgMock,
    useDialog: () => ({ warning: vi.fn() })
  }
})

// happy-dom 无 Blob URL 实现：桩掉下载链路（createObjectURL/revokeObjectURL/锚点点击）
const createObjectURL = vi.fn(() => 'blob:mock')
const revokeObjectURL = vi.fn()

type ExportVm = {
  showExportModal: boolean
  exportPassword: string
  handleExport: () => void
  confirmExport: () => Promise<void>
}

type DefaultsVm = ExportVm & {
  modelDefaults: { chatModel: string | null; embeddingModel: string | null; imageModel: string | null; videoModel: string | null }
  saveModelDefaults: () => Promise<void>
}

async function mountTab(): Promise<ExportVm> {
  const wrapper = mount(ProviderManageTab)
  await flushPromises()
  return wrapper.vm as unknown as ExportVm
}

describe('ProviderManageTab 导出密码确认（修复VIII B4）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    apiMock.listProviders.mockResolvedValue({
      data: { code: 200, msg: 'success', data: [] }
    })
    sysApiMock.getLlmModelDefaults.mockResolvedValue({
      data: { code: 200, msg: 'success', data: { chatModel: null, embeddingModel: null, imageModel: null, videoModel: null } }
    })
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectURL, configurable: true })
    Object.defineProperty(URL, 'revokeObjectURL', { value: revokeObjectURL, configurable: true })
  })

  it('导出入口：弹密码确认框且每次重置密码（不复述上次输入）', async () => {
    const vm = await mountTab()
    vm.exportPassword = '上次的密码'
    vm.handleExport()
    expect(vm.showExportModal).toBe(true)
    expect(vm.exportPassword).toBe('')
  })

  it('空密码：warning 拦截，不触达导出接口（密码必填）', async () => {
    const vm = await mountTab()
    vm.handleExport()
    vm.exportPassword = ''
    await vm.confirmExport()
    expect(apiMock.exportProviders).not.toHaveBeenCalled()
    expect(msgMock.warning).toHaveBeenCalledWith('请输入当前登录密码')
  })

  it('密码错误/导出失败：弹窗保留可重试，口径化错误提示', async () => {
    const vm = await mountTab()
    vm.handleExport()
    apiMock.exportProviders.mockRejectedValueOnce(new Error('密码错误') as never)
    vm.exportPassword = 'wrong-pw'
    await vm.confirmExport()

    expect(apiMock.exportProviders).toHaveBeenCalledWith('wrong-pw')
    expect(vm.showExportModal).toBe(true)
    expect(msgMock.error).toHaveBeenCalledWith('密码错误或导出失败，请重试')

    // 原地重试成功：关窗 + 下载链路触发
    apiMock.exportProviders.mockResolvedValueOnce({ data: new Blob(['[]']) } as never)
    vm.exportPassword = 'admin123'
    await vm.confirmExport()
    expect(vm.showExportModal).toBe(false)
    expect(createObjectURL).toHaveBeenCalledTimes(1)
  })

  it('密码正确：POST 密码入 body、成功关窗清密码并触发浏览器下载', async () => {
    const vm = await mountTab()
    vm.handleExport()
    apiMock.exportProviders.mockResolvedValueOnce({ data: new Blob(['[{"name":"deepseek"}]']) } as never)
    vm.exportPassword = 'admin123'
    await vm.confirmExport()

    // 密码作为唯一参数传 API 层（api/llm.ts 组 { password } body POST——绝不能进 URL）
    expect(apiMock.exportProviders).toHaveBeenCalledTimes(1)
    expect(apiMock.exportProviders).toHaveBeenCalledWith('admin123')
    expect(vm.showExportModal).toBe(false)
    expect(vm.exportPassword).toBe('')
    expect(msgMock.success).toHaveBeenCalledWith('导出成功')
    expect(createObjectURL).toHaveBeenCalledTimes(1)
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock')
  })
})

describe('ProviderManageTab 默认视频模型（全局默认模型扩展）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    apiMock.listProviders.mockResolvedValue({
      data: {
        code: 200, msg: 'success',
        data: [{
          id: 1, name: 'minimax-h3', displayName: 'MiniMax H3', status: 'ACTIVE',
          category: 'VIDEO', protocol: 'minimax', models: '["minimax-h3","minimax-h3-regeneration"]', sortOrder: 0
        }]
      }
    })
    sysApiMock.getLlmModelDefaults.mockResolvedValue({
      data: { code: 200, msg: 'success', data: { chatModel: null, embeddingModel: null, imageModel: null, videoModel: null } }
    })
  })

  it('渲染「默认视频模型」下拉：候选 = 启用 VIDEO provider 模型全集（含附属档）', async () => {
    const wrapper = mount(ProviderManageTab)
    await flushPromises()
    expect(wrapper.text()).toContain('默认视频模型')
    const vm = wrapper.vm as unknown as DefaultsVm
    // videoModelOptions 是 setup 闭包 computed——经 vm 可达（script setup 绑定挂 proxy）
    const options = (vm as unknown as { videoModelOptions: { label: string; value: string }[] }).videoModelOptions
    expect(options.map(o => o.value)).toEqual(['minimax-h3', 'minimax-h3-regeneration'])
  })

  it('选中默认视频模型 → PUT 全量默认模型（body 含 videoModel）', async () => {
    sysApiMock.updateLlmModelDefaults.mockResolvedValueOnce({
      data: { code: 200, msg: 'success', data: { chatModel: null, embeddingModel: null, imageModel: null, videoModel: 'minimax-h3' } }
    })
    const wrapper = mount(ProviderManageTab)
    await flushPromises()
    const vm = wrapper.vm as unknown as DefaultsVm
    vm.modelDefaults.videoModel = 'minimax-h3'
    await vm.saveModelDefaults()
    expect(sysApiMock.updateLlmModelDefaults).toHaveBeenCalledWith(
      expect.objectContaining({ videoModel: 'minimax-h3' }))
    expect(msgMock.success).toHaveBeenCalledWith('默认模型已更新')
    expect(vm.modelDefaults.videoModel).toBe('minimax-h3')
  })
})
