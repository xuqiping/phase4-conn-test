import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import AssetDetailDrawer from './AssetDetailDrawer.vue'
import ModelSelector from '@/components/chat/ModelSelector.vue'
import { assetApi, assetBridgeApi, scriptApi, versionApi } from '@/api/assets'
import type { AxiosResponse } from 'axios'
import type { AssetVO } from '@/types/asset'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/canvas', () => ({
  fetchCanvasPreview: vi.fn().mockResolvedValue('blob:preview')
}))

const { requestGet } = vi.hoisted(() => ({ requestGet: vi.fn() }))
vi.mock('@/api/request', () => ({ default: { get: requestGet }, request: { get: requestGet } }))

vi.mock('@/api/assets', () => ({
  assetApi: { get: vi.fn() },
  assetBridgeApi: { usages: vi.fn() },
  scriptApi: { breakdown: vi.fn() },
  versionApi: { lock: vi.fn(), unlock: vi.fn(), archive: vi.fn(), unarchive: vi.fn() }
}))

// FR-006：拆分场弹窗挂了 ModelSelector（拉可用模型列表），测试环境不打真请求
vi.mock('@/api/llm', () => ({
  llmApi: { listAvailableModels: vi.fn().mockResolvedValue({ data: { data: [] } }) }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function mkAsset(over: Partial<AssetVO> = {}): AssetVO {
  return {
    id: 5,
    projectId: 7,
    mediaType: 'PROMPT',
    name: '提示词A',
    description: 'desc',
    tags: ['t1'],
    status: 'DRAFT',
    content: '{"k":"v"}',
    genMeta: null,
    currentVersion: 1,
    roleKeys: ['人物'],
    fileId: undefined,
    createdAt: '2026-08-05',
    ...over
  }
}

async function settle() {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

async function mountDrawer(props: Partial<{ show: boolean; assetId: number | null; canEdit: boolean }> = {}) {
  const wrapper = mount(AssetDetailDrawer, {
    props: { show: true, assetId: 5, canEdit: true, ...props }
  })
  await settle()
  return wrapper
}

describe('AssetDetailDrawer (S10-10a)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(assetApi.get).mockResolvedValue(response({ code: 200, message: 'ok', data: mkAsset() }))
    vi.mocked(assetBridgeApi.usages).mockResolvedValue(response({ code: 200, message: 'ok', data: [] }))
  })

  it('加载资产 + usages', async () => {
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { asset: AssetVO | null }
    expect(assetApi.get).toHaveBeenCalledWith(5)
    expect(assetBridgeApi.usages).toHaveBeenCalledWith(5)
    expect(vm.asset?.id).toBe(5)
  })

  it('IMAGE 资产拉预览 objectURL', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ mediaType: 'IMAGE', fileId: 'fid-1' }) })
    )
    const { fetchCanvasPreview } = await import('@/api/canvas')
    const wrapper = await mountDrawer()
    expect(fetchCanvasPreview).toHaveBeenCalledWith('fid-1')
    expect((wrapper.vm as unknown as { previewUrl: string | null }).previewUrl).toBe('blob:preview')
  })

  it('定稿调 versionApi.lock + emit changed（L2）', async () => {
    vi.mocked(versionApi.lock).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ status: 'LOCKED' }) })
    )
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { doAction: (a: 'lock' | 'unlock' | 'archive' | 'unarchive') => Promise<void> }
    await vm.doAction('lock')
    expect(versionApi.lock).toHaveBeenCalledWith(5)
    expect(wrapper.emitted('changed')).toBeTruthy()
    expect((wrapper.vm as unknown as { asset: AssetVO | null }).asset?.status).toBe('LOCKED')
  })

  it('状态机返 meta-only（content/fileId=null）→ 保留抽屉已加载值不丢失（FIX-B）', async () => {
    // 初始加载 IMAGE 资产带 fileId + content
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ mediaType: 'IMAGE', fileId: 'fid-1', content: '{"k":"v"}' }) })
    )
    // lock 返 meta-only：content=null + fileId=null（懒加载语义）
    vi.mocked(versionApi.lock).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ status: 'LOCKED', content: null as unknown as string, fileId: null as unknown as string }) })
    )
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { doAction: (a: 'lock') => Promise<void>; asset: AssetVO | null }
    await vm.doAction('lock')
    expect(vm.asset?.status).toBe('LOCKED')
    // 关键：保留旧值，不显「无正文」/下载不失效
    expect(vm.asset?.content).toBe('{"k":"v"}')
    expect(vm.asset?.fileId).toBe('fid-1')
  })

  it('归档调 versionApi.archive（L3）', async () => {
    vi.mocked(versionApi.archive).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ status: 'ARCHIVED' }) })
    )
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { doAction: (a: 'lock' | 'unlock' | 'archive' | 'unarchive') => Promise<void> }
    await vm.doAction('archive')
    expect(versionApi.archive).toHaveBeenCalledWith(5)
  })

  it('下载调 request.get blob', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ mediaType: 'VIDEO', fileId: 'fid-2' }) })
    )
    requestGet.mockResolvedValue(response(new Blob(['x'])))
    const wrapper = await mountDrawer()

    // 挂载后再 spy createElement，且仅对 'a' 返假 anchor（避免破坏 Vue 渲染）
    const clickSpy = vi.fn()
    const anchor = { click: clickSpy, remove: vi.fn(), href: '', download: '' } as unknown as HTMLAnchorElement
    const realCreate = document.createElement.bind(document)
    const createSpy = vi.spyOn(document, 'createElement').mockImplementation((tag: string) =>
      tag === 'a' ? anchor : realCreate(tag)
    )
    vi.spyOn(document.body, 'appendChild').mockImplementation(() => anchor)

    const vm = wrapper.vm as unknown as { download: () => Promise<void> }
    await vm.download()
    expect(requestGet).toHaveBeenCalledWith('/files/fid-2', { responseType: 'blob' })
    expect(clickSpy).toHaveBeenCalled()
    createSpy.mockRestore()
  })
})

describe('AssetDetailDrawer (FR-006 AI 拆分场)', () => {
  type BreakdownVm = {
    showBreakdown: boolean
    breakdownModel: string
    doBreakdown: () => Promise<void>
  }

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ mediaType: 'SCRIPT' }) })
    )
    vi.mocked(assetBridgeApi.usages).mockResolvedValue(response({ code: 200, message: 'ok', data: [] }))
  })

  it('SCRIPT + canEdit → 渲染「AI 拆分场」按钮；PROMPT / 无写权不渲染', async () => {
    // NDrawer teleport 到 body，须 attachTo + document.body 查询
    const hasBtn = () =>
      Array.from(document.body.querySelectorAll('button')).some((b) => b.textContent?.includes('AI 拆分场'))
    const mountAttached = (canEdit = true) =>
      mount(AssetDetailDrawer, { props: { show: true, assetId: 5, canEdit }, attachTo: document.body })

    const scriptWrapper = mountAttached() // beforeEach 已 mock SCRIPT 资产
    await settle()
    expect(hasBtn()).toBe(true)
    scriptWrapper.unmount()

    vi.mocked(assetApi.get).mockResolvedValue(response({ code: 200, message: 'ok', data: mkAsset() }))
    const promptWrapper = mountAttached() // mkAsset 默认 PROMPT
    await settle()
    expect(hasBtn()).toBe(false)
    promptWrapper.unmount()

    const viewerWrapper = mountAttached(false)
    await settle()
    expect(hasBtn()).toBe(false)
    viewerWrapper.unmount()
  })

  it('选模型拆分 → scriptApi.breakdown 带 model + 重载详情 + emit changed', async () => {
    vi.mocked(scriptApi.breakdown).mockResolvedValue(
      response({ code: 200, message: 'ok', data: { scenes: [{}, {}], model: 'm-x', version: 3 } as never })
    )
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as BreakdownVm

    // 打开弹窗 + 通过弹窗内 ModelSelector 选模型（v-model ↔ breakdownModel）
    vm.showBreakdown = true
    await wrapper.vm.$nextTick()
    wrapper.findComponent(ModelSelector).vm.$emit('update:modelValue', 'm-x')

    await vm.doBreakdown()
    expect(scriptApi.breakdown).toHaveBeenCalledWith(5, 'm-x')
    // 重载详情（assetApi.get 二次调用）+ 通知父
    expect(vi.mocked(assetApi.get).mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(wrapper.emitted('changed')).toBeTruthy()
    expect(vm.showBreakdown).toBe(false)
  })

  it('留空模型 → breakdown 不传 model（走后端 asset.script-model 默认）', async () => {
    vi.mocked(scriptApi.breakdown).mockResolvedValue(
      response({ code: 200, message: 'ok', data: { scenes: [{}], model: 'default-model', version: 2 } as never })
    )
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as BreakdownVm

    await vm.doBreakdown()
    expect(scriptApi.breakdown).toHaveBeenCalledWith(5, undefined)
  })

  it('拆分失败 → 错误提示且不关弹窗、不 emit changed', async () => {
    vi.mocked(scriptApi.breakdown).mockRejectedValue(new Error('boom'))
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as BreakdownVm
    vm.showBreakdown = true

    await vm.doBreakdown()
    expect(messageMock.error).toHaveBeenCalledWith('拆分失败')
    expect(vm.showBreakdown).toBe(true)
    expect(wrapper.emitted('changed')).toBeUndefined()
  })
})
