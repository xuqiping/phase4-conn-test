import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import AssetPicker from './AssetPicker.vue'
import { projectApi, publicPoolApi, assetApi, assetBridgeApi } from '@/api/assets'
import type { AxiosResponse } from 'axios'
import type { AssetProjectVO, AssetVO, PublicProjectSummaryVO, ResolveVO } from '@/types/asset'
import type { CanvasNode } from '@/types/canvas'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/assets', () => ({
  projectApi: { list: vi.fn() },
  publicPoolApi: { list: vi.fn() },
  assetApi: { list: vi.fn() },
  assetBridgeApi: { resolve: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function mkProject(id: number): AssetProjectVO {
  return {
    id,
    name: `项目${id}`,
    ownerId: 1,
    narrativeRoles: ['人物'],
    mediaTypes: [{ key: '提示词', category: 'TEXT' }],
    role: 'OWNER',
    createdAt: '2026-08-05'
  }
}

function mkPublic(id: number, over: Partial<PublicProjectSummaryVO> = {}): PublicProjectSummaryVO {
  return {
    id,
    name: `公共项目${id}`,
    description: `摘要${id}`,
    publicAccessMode: 'OPEN',
    publishedBy: 8,
    publisherUsername: 'publisher',
    publishedAt: '2026-08-06',
    publishedByAdmin: false,
    assetCount: 12,
    myRequestStatus: null,
    usable: true,
    ...over
  }
}

function mkAsset(id: number, over: Partial<AssetVO> = {}): AssetVO {
  return {
    id, projectId: 10, mediaType: '提示词', name: `资产${id}`, status: 'DRAFT',
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
  const wrapper = mount(AssetPicker, {
    props: { show: true, node, canvasId },
    global: { stubs: { teleport: true } }
  })
  await settle()
  return wrapper
}

describe('AssetPicker (画布资产选择器)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(projectApi.list).mockReset()
    vi.mocked(publicPoolApi.list).mockReset()
    vi.mocked(assetApi.list).mockReset()
    vi.mocked(assetBridgeApi.resolve).mockReset()
    vi.mocked(projectApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [mkProject(10), mkProject(11)] })
    )
    vi.mocked(publicPoolApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [mkPublic(20)] })
    )
    vi.mocked(assetApi.list).mockResolvedValue(pageResp([mkAsset(1), mkAsset(2)]))
  })

  it('打开时同时加载本地与公共项目，任一路失败不清空另一路', async () => {
    vi.mocked(projectApi.list).mockRejectedValueOnce(new Error('local offline'))
    const localFailed = await mountPicker(mkNode('text'))
    const localVm = localFailed.vm as unknown as {
      projects: AssetProjectVO[]
      publicProjects: PublicProjectSummaryVO[]
      localError: string
      publicError: string
    }
    expect(projectApi.list).toHaveBeenCalledOnce()
    expect(publicPoolApi.list).toHaveBeenCalledOnce()
    expect(localVm.projects).toEqual([])
    expect(localVm.publicProjects.map((p) => p.id)).toEqual([20])
    expect(localVm.localError).toContain('本地项目列表加载失败')
    expect(localVm.publicError).toBe('')
    localFailed.unmount()

    vi.mocked(projectApi.list).mockResolvedValueOnce(
      response({ code: 200, message: 'ok', data: [mkProject(12)] })
    )
    vi.mocked(publicPoolApi.list).mockRejectedValueOnce(new Error('public offline'))
    const publicFailed = await mountPicker(mkNode('text'))
    const publicVm = publicFailed.vm as unknown as {
      projects: AssetProjectVO[]
      publicProjects: PublicProjectSummaryVO[]
      localError: string
      publicError: string
    }
    expect(publicVm.projects.map((p) => p.id)).toEqual([12])
    expect(publicVm.publicProjects).toEqual([])
    expect(publicVm.localError).toBe('')
    expect(publicVm.publicError).toContain('公共池项目列表加载失败')
  })

  it('公共 options 明示官方、访问模式与不可用原因，并禁用未获批项目', async () => {
    vi.mocked(publicPoolApi.list).mockResolvedValueOnce(response({ code: 200, message: 'ok', data: [
      mkPublic(20, { publishedByAdmin: true }),
      mkPublic(21, { publicAccessMode: 'APPROVAL_REQUIRED', usable: false, myRequestStatus: 'PENDING' }),
      mkPublic(22, { publicAccessMode: 'APPROVAL_REQUIRED', usable: false, myRequestStatus: 'REJECTED' }),
      mkPublic(23, { publicAccessMode: 'APPROVAL_REQUIRED', usable: false, myRequestStatus: 'REVOKED' })
    ] }))
    const wrapper = await mountPicker(mkNode('text'))
    const vm = wrapper.vm as unknown as {
      switchSource: (source: 'local' | 'public') => void
      projectOptions: Array<{ label: string; value: number; disabled?: boolean }>
    }
    vm.switchSource('public')
    await wrapper.vm.$nextTick()

    expect(vm.projectOptions.find((o) => o.value === 20)?.label).toContain('官方发布')
    expect(vm.projectOptions.find((o) => o.value === 20)?.label).toContain('直接使用')
    expect(vm.projectOptions.find((o) => o.value === 21)).toMatchObject({ disabled: true })
    expect(vm.projectOptions.find((o) => o.value === 21)?.label).toContain('等待审批')
    expect(vm.projectOptions.find((o) => o.value === 22)?.label).toContain('被拒绝')
    expect(vm.projectOptions.find((o) => o.value === 23)?.label).toContain('已撤销')
  })

  it('真实来源按钮切到公共池会清旧选择；可用项目拉资产，不可用项目不拉', async () => {
    vi.mocked(publicPoolApi.list).mockResolvedValueOnce(response({ code: 200, message: 'ok', data: [
      mkPublic(20),
      mkPublic(21, { publicAccessMode: 'APPROVAL_REQUIRED', usable: false, myRequestStatus: 'PENDING' })
    ] }))
    const wrapper = await mountPicker(mkNode('image'))
    const vm = wrapper.vm as unknown as {
      source: 'local' | 'public'
      projectId: number | null
      keyword: string
      assets: AssetVO[]
      onProjectChange: (value: number | null) => Promise<void>
    }
    vm.projectId = 10
    vm.keyword = '旧关键词'
    vm.assets = [mkAsset(9)]

    const publicButton = wrapper.findAll('button').find((button) => button.text().includes('公共池'))
    expect(publicButton).toBeTruthy()
    await publicButton!.trigger('click')
    expect(vm.source).toBe('public')
    expect(vm.projectId).toBeNull()
    expect(vm.keyword).toBe('')
    expect(vm.assets).toEqual([])

    vi.mocked(assetApi.list).mockClear()
    await vm.onProjectChange(21)
    expect(assetApi.list).not.toHaveBeenCalled()
    expect(vm.projectId).toBeNull()

    await vm.onProjectChange(20)
    expect(assetApi.list).toHaveBeenCalledWith(20, expect.objectContaining({ type: '图片', page: 1, size: 100 }))
  })

  it('慢旧来源列表与慢旧资产响应不会覆盖新会话或新来源', async () => {
    const oldLocal = deferred<AxiosResponse<{ code: number; message: string; data: AssetProjectVO[] }>>()
    const oldPublic = deferred<AxiosResponse<{ code: number; message: string; data: PublicProjectSummaryVO[] }>>()
    vi.mocked(projectApi.list)
      .mockReturnValueOnce(oldLocal.promise)
      .mockResolvedValueOnce(response({ code: 200, message: 'ok', data: [mkProject(12)] }))
    vi.mocked(publicPoolApi.list)
      .mockReturnValueOnce(oldPublic.promise)
      .mockResolvedValueOnce(response({ code: 200, message: 'ok', data: [mkPublic(22)] }))

    const wrapper = mount(AssetPicker, {
      props: { show: true, node: mkNode('text') },
      global: { stubs: { teleport: true } }
    })
    await wrapper.setProps({ show: false })
    await wrapper.setProps({ show: true })
    await settle()
    const vm = wrapper.vm as unknown as {
      projects: AssetProjectVO[]
      publicProjects: PublicProjectSummaryVO[]
      switchSource: (source: 'local' | 'public') => void
      onProjectChange: (value: number | null) => Promise<void>
      assets: AssetVO[]
    }
    expect(vm.projects.map((p) => p.id)).toEqual([12])
    expect(vm.publicProjects.map((p) => p.id)).toEqual([22])

    oldLocal.resolve(response({ code: 200, message: 'ok', data: [mkProject(99)] }))
    oldPublic.resolve(response({ code: 200, message: 'ok', data: [mkPublic(99)] }))
    await settle()
    expect(vm.projects.map((p) => p.id)).toEqual([12])
    expect(vm.publicProjects.map((p) => p.id)).toEqual([22])

    const oldAssets = deferred<ReturnType<typeof pageResp>>()
    vi.mocked(assetApi.list)
      .mockReturnValueOnce(oldAssets.promise)
      .mockResolvedValueOnce(pageResp([mkAsset(22, { projectId: 22 })]))
    const oldAssetLoad = vm.onProjectChange(12)
    await settle()
    vm.switchSource('public')
    await vm.onProjectChange(22)
    expect(vm.assets.map((a) => a.id)).toEqual([22])
    oldAssets.resolve(pageResp([mkAsset(99, { projectId: 12 })]))
    await oldAssetLoad
    expect(vm.assets.map((a) => a.id)).toEqual([22])
  })

  it('节点类型映射并按 mediaType 拉资产列表', async () => {
    const wrapper = await mountPicker(mkNode('video'))
    const vm = wrapper.vm as unknown as {
      mediaType: string
      onProjectChange: (value: number | null) => Promise<void>
      assets: AssetVO[]
    }
    expect(vm.mediaType).toBe('视频')
    await vm.onProjectChange(10)
    expect(assetApi.list).toHaveBeenCalledWith(10, expect.objectContaining({ type: '视频', page: 1, size: 100 }))
    expect(vm.assets.length).toBe(2)
  })

  it('onPick resolve 当前版本并原样 emit 返回的 version', async () => {
    const resolve: ResolveVO = { assetId: 1, mediaType: '图片', version: 7, fileId: 'f-1', name: '资产1' }
    vi.mocked(assetBridgeApi.resolve).mockResolvedValue(response({ code: 200, message: 'ok', data: resolve }))
    const node = mkNode('image')
    const wrapper = await mountPicker(node, 77)
    const vm = wrapper.vm as unknown as { onPick: (a: AssetVO) => Promise<void> }
    await vm.onPick(mkAsset(1, { mediaType: '图片', currentVersion: 9 }))

    expect(assetBridgeApi.resolve).toHaveBeenCalledWith(1, { canvasId: 77, nodeId: node.id })
    const emitted = wrapper.emitted('picked')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toMatchObject({ node, resolve: { version: 7 } })
    expect((emitted![0][0] as { resolve: ResolveVO }).resolve).toBe(resolve)
    expect(wrapper.emitted('update:show')).toBeTruthy()
  })

  it('resolve 报错时提示且不 emit picked', async () => {
    vi.mocked(assetBridgeApi.resolve).mockRejectedValue({ msg: '无权访问' })
    const wrapper = await mountPicker(mkNode('text'))
    const vm = wrapper.vm as unknown as { onPick: (a: AssetVO) => Promise<void> }
    await vm.onPick(mkAsset(1))
    expect(messageMock.error).toHaveBeenCalledWith('无权访问')
    expect(wrapper.emitted('picked')).toBeFalsy()
  })
})
