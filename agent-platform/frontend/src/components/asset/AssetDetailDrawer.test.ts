import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import AssetDetailDrawer from './AssetDetailDrawer.vue'
import { assetApi, assetBridgeApi, versionApi } from '@/api/assets'
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
  versionApi: { lock: vi.fn(), unlock: vi.fn(), archive: vi.fn(), unarchive: vi.fn() }
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
