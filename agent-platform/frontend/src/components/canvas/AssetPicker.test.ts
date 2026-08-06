import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import AssetPicker from './AssetPicker.vue'
import { projectApi, assetApi, assetBridgeApi } from '@/api/assets'
import type { AxiosResponse } from 'axios'
import type { AssetProjectVO, AssetVO, ResolveVO } from '@/types/asset'
import type { CanvasNode } from '@/types/canvas'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/assets', () => ({
  projectApi: { list: vi.fn() },
  assetApi: { list: vi.fn() },
  assetBridgeApi: { resolve: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function mkProject(id: number): AssetProjectVO {
  return {
    id,
    name: `项目${id}`,
    ownerId: 1,
    narrativeRoles: ['人物'],
    mediaTypes: [{ key: 'PROMPT', category: 'TEXT' }],
    role: 'OWNER',
    createdAt: '2026-08-05'
  }
}

function mkAsset(id: number, over: Partial<AssetVO> = {}): AssetVO {
  return {
    id, projectId: 10, mediaType: 'PROMPT', name: `资产${id}`, status: 'DRAFT',
    currentVersion: 1, roleKeys: [], content: null, genMeta: null, createdAt: '2026-08-05', ...over
  }
}

function mkNode(type: string): CanvasNode {
  return { id: 'node-1', type, position: { x: 0, y: 0 }, data: { label: '节点' } }
}

function pageResp(records: AssetVO[]) {
  return response({ code: 200, message: 'ok', data: { records, total: records.length, page: 1, size: 100 } })
}

async function settle() {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

async function mountPicker(node: CanvasNode, canvasId?: number) {
  const wrapper = mount(AssetPicker, { props: { show: true, node, canvasId } })
  await settle()
  return wrapper
}

describe('AssetPicker (S12-b 资产选择器)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(projectApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [mkProject(10), mkProject(11)] })
    )
    vi.mocked(assetApi.list).mockResolvedValue(pageResp([mkAsset(1), mkAsset(2)]))
  })

  it('节点类型→资产类型映射（text→PROMPT / video→VIDEO）', async () => {
    const w1 = await mountPicker(mkNode('text'))
    expect((w1.vm as unknown as { mediaType: string }).mediaType).toBe('PROMPT')
    const w2 = await mountPicker(mkNode('video'))
    expect((w2.vm as unknown as { mediaType: string }).mediaType).toBe('VIDEO')
  })

  it('选项目 → 按 mediaType 拉资产列表', async () => {
    const wrapper = await mountPicker(mkNode('image'))
    const vm = wrapper.vm as unknown as {
      projectId: number | null
      loadAssets: () => Promise<void>
      assets: AssetVO[]
    }
    vm.projectId = 10
    await vm.loadAssets()
    expect(assetApi.list).toHaveBeenCalledWith(10, expect.objectContaining({ type: 'IMAGE', page: 1, size: 100 }))
    expect(vm.assets.length).toBe(2)
  })

  it('关键词随 list 请求下传（debounce 后）', async () => {
    vi.useFakeTimers()
    const wrapper = await mountPicker(mkNode('text'))
    const vm = wrapper.vm as unknown as {
      projectId: number | null
      keyword: string
      onKeywordChange: () => void
      loadAssets: () => Promise<void>
    }
    vm.projectId = 10
    vm.keyword = '老板娘'
    vm.onKeywordChange()
    vi.advanceTimersByTime(300)
    await vm.loadAssets()
    expect(assetApi.list).toHaveBeenCalledWith(10, expect.objectContaining({ q: '老板娘' }))
    vi.useRealTimers()
  })

  it('onPick → resolve → emit picked + 关弹窗', async () => {
    const resolve: ResolveVO = { assetId: 1, mediaType: 'IMAGE', version: 1, fileId: 'f-1', name: '资产1' }
    vi.mocked(assetBridgeApi.resolve).mockResolvedValue(response({ code: 200, message: 'ok', data: resolve }))
    const node = mkNode('image')
    const wrapper = await mountPicker(node, 77)
    const vm = wrapper.vm as unknown as { onPick: (a: AssetVO) => Promise<void> }
    await vm.onPick(mkAsset(1, { mediaType: 'IMAGE' }))

    // resolve 带 canvasId+nodeId → 后端落 REFERENCE 绑定（L6 双向追溯）
    expect(assetBridgeApi.resolve).toHaveBeenCalledWith(1, { canvasId: 77, nodeId: node.id })
    const emitted = wrapper.emitted('picked')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toMatchObject({ node, resolve })
    expect(wrapper.emitted('update:show')).toBeTruthy()
  })

  it('resolve 报错 → message.error 不 emit picked', async () => {
    vi.mocked(assetBridgeApi.resolve).mockRejectedValue({ msg: '无权访问' })
    const wrapper = await mountPicker(mkNode('text'))
    const vm = wrapper.vm as unknown as { onPick: (a: AssetVO) => Promise<void> }
    await vm.onPick(mkAsset(1))
    expect(messageMock.error).toHaveBeenCalledWith('无权访问')
    expect(wrapper.emitted('picked')).toBeFalsy()
  })
})
